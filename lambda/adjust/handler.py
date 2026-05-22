"""Adjust Lambda handler — atomic stock delta with negative-stock guard."""

from __future__ import annotations

import json
import time
import uuid
from datetime import datetime, timezone
from typing import Any

from botocore.exceptions import ClientError

from shared.auth import get_user_sub, resolve_household
from shared.db import table
from shared.errors import (
    AppError,
    InsufficientStockError,
    NotFoundError,
    ValidationError,
    error_response,
    internal_error_response,
)
from shared.validators import validate_integer_range, validate_required_fields

_TTL_90_DAYS = 90 * 24 * 60 * 60


def handler(event: dict[str, Any], context: Any) -> dict[str, Any]:
    """POST /adjust — apply an atomic delta to a product's currentQty."""
    try:
        user_sub = get_user_sub(event)
        hh_id = resolve_household(user_sub)

        body = _parse_body(event)
        validate_required_fields(body, ["productId", "delta"])

        product_id: str = body["productId"]
        if not isinstance(product_id, str) or not product_id.strip():
            raise ValidationError(
                "productId must be a non-empty string",
                details={"field": "productId"},
            )

        delta = validate_integer_range(body["delta"], "delta", min_val=-9999, max_val=9999)
        if delta == 0:
            raise ValidationError(
                "delta must be non-zero",
                details={"field": "delta"},
            )

        # Apply atomic ADD with negative-stock guard
        try:
            resp = table.update_item(
                Key={"PK": f"HH#{hh_id}", "SK": f"PROD#{product_id}"},
                UpdateExpression="ADD currentQty :delta",
                ConditionExpression=(
                    "attribute_exists(SK) AND "
                    "(currentQty + :delta >= :zero OR :delta > :zero)"
                ),
                ExpressionAttributeValues={":delta": delta, ":zero": 0},
                ReturnValues="ALL_NEW",
            )
        except ClientError as exc:
            if exc.response["Error"]["Code"] == "ConditionalCheckFailedException":
                # Determine whether the product doesn't exist or stock is insufficient
                current_qty = _get_current_qty(hh_id, product_id)
                if current_qty is None:
                    raise NotFoundError(f"Product {product_id} not found") from exc
                raise InsufficientStockError(
                    "Current quantity cannot go below zero",
                    details={"currentQty": current_qty},
                ) from exc
            raise

        updated_item = resp["Attributes"]
        new_qty: int = int(updated_item["currentQty"])

        # Write history record
        now = datetime.now(timezone.utc)
        timestamp = now.isoformat(timespec="seconds")
        hist_id = f"hist_{uuid.uuid4().hex[:12]}"
        ttl = int(time.time()) + _TTL_90_DAYS

        # Resolve user display name from membership
        user_name = _get_user_name(hh_id, user_sub)
        product_name = updated_item.get("name", "")

        table.put_item(Item={
            "PK": f"HH#{hh_id}",
            "SK": f"HIST#{timestamp}#{hist_id}",
            "productId": product_id,
            "productName": product_name,
            "delta": delta,
            "userId": user_sub,
            "userName": user_name,
            "timestamp": timestamp,
            "ttl": ttl,
        })

        return _response(200, {
            "productId": product_id,
            "currentQty": new_qty,
            "delta": delta,
            "historyId": hist_id,
        })

    except AppError as exc:
        return error_response(exc)
    except Exception:
        return internal_error_response()


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _parse_body(event: dict[str, Any]) -> dict[str, Any]:
    """Parse the JSON body from the API Gateway event."""
    raw = event.get("body")
    if not raw:
        raise ValidationError("Request body is required")
    try:
        body = json.loads(raw)
    except (json.JSONDecodeError, TypeError) as exc:
        raise ValidationError("Request body must be valid JSON") from exc
    if not isinstance(body, dict):
        raise ValidationError("Request body must be a JSON object")
    return body


def _get_current_qty(hh_id: str, product_id: str) -> int | None:
    """Fetch currentQty for error reporting. Returns None if product doesn't exist."""
    resp = table.get_item(
        Key={"PK": f"HH#{hh_id}", "SK": f"PROD#{product_id}"},
        ProjectionExpression="currentQty",
    )
    item = resp.get("Item")
    if not item:
        return None
    return int(item.get("currentQty", 0))


def _get_user_name(hh_id: str, user_sub: str) -> str:
    """Resolve display name from the membership record."""
    resp = table.get_item(
        Key={"PK": f"HH#{hh_id}", "SK": f"MBR#{user_sub}"},
        ProjectionExpression="displayName",
    )
    item = resp.get("Item")
    if item and "displayName" in item:
        return item["displayName"]
    return user_sub


def _response(status_code: int, body: Any) -> dict[str, Any]:
    """Build an API Gateway proxy response."""
    return {
        "statusCode": status_code,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps(body),
    }
