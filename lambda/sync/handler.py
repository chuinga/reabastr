"""Sync Lambda handler — full state retrieval and batch delta processing."""

from __future__ import annotations

import json
import time
import uuid
from datetime import datetime, timezone
from typing import Any

from boto3.dynamodb.conditions import Key
from botocore.exceptions import ClientError

from shared.auth import get_user_sub, resolve_household
from shared.db import table
from shared.errors import (
    AppError,
    ValidationError,
    error_response,
    internal_error_response,
)
from shared.validators import validate_integer_range

_TTL_90_DAYS = 90 * 24 * 60 * 60


def handler(event: dict[str, Any], context: Any) -> dict[str, Any]:
    """Route to GET /sync or POST /sync/batch based on method + path."""
    try:
        user_sub = get_user_sub(event)
        hh_id = resolve_household(user_sub)

        method = event.get("httpMethod", "").upper()
        path = event.get("path", "")

        if method == "GET" and path.endswith("/sync"):
            return _handle_get_sync(hh_id)
        elif method == "POST" and path.endswith("/sync/batch"):
            return _handle_post_batch(hh_id, event, user_sub)
        else:
            return _response(404, {"error": "NOT_FOUND", "message": "Route not found"})

    except AppError as exc:
        return error_response(exc)
    except Exception:
        return internal_error_response()


# ---------------------------------------------------------------------------
# GET /sync — full household state
# ---------------------------------------------------------------------------


def _handle_get_sync(hh_id: str) -> dict[str, Any]:
    """Query all items in the household partition and return categorized."""
    items: list[dict[str, Any]] = []
    kwargs: dict[str, Any] = {
        "KeyConditionExpression": Key("PK").eq(f"HH#{hh_id}"),
    }

    # Paginate through all items in the partition
    while True:
        resp = table.query(**kwargs)
        items.extend(resp.get("Items", []))
        last_key = resp.get("LastEvaluatedKey")
        if not last_key:
            break
        kwargs["ExclusiveStartKey"] = last_key

    # Categorize by SK prefix
    products: list[dict[str, Any]] = []
    categories: list[dict[str, Any]] = []
    ean_mappings: list[dict[str, Any]] = []
    household: dict[str, Any] | None = None
    members: list[dict[str, Any]] = []

    for item in items:
        sk: str = item.get("SK", "")
        # Remove internal keys from response
        clean = {k: v for k, v in item.items() if k not in ("PK",)}

        if sk == "#META":
            household = clean
        elif sk.startswith("PROD#"):
            products.append(clean)
        elif sk.startswith("CAT#"):
            categories.append(clean)
        elif sk.startswith("EAN#"):
            ean_mappings.append(clean)
        elif sk.startswith("MBR#"):
            members.append(clean)
        # Skip HIST# and SHARE# items — not needed for sync

    return _response(200, {
        "products": products,
        "categories": categories,
        "ean_mappings": ean_mappings,
        "household": household,
        "members": members,
    })


# ---------------------------------------------------------------------------
# POST /sync/batch — process multiple delta events
# ---------------------------------------------------------------------------


def _handle_post_batch(
    hh_id: str, event: dict[str, Any], user_sub: str
) -> dict[str, Any]:
    """Process a batch of delta events sequentially."""
    body = _parse_body(event)
    events = body.get("events")

    if not isinstance(events, list):
        raise ValidationError(
            "events must be a list",
            details={"field": "events"},
        )
    if len(events) == 0:
        raise ValidationError(
            "events must not be empty",
            details={"field": "events"},
        )

    # Resolve user display name once for all history records
    user_name = _get_user_name(hh_id, user_sub)

    results: list[dict[str, Any]] = []
    failed = 0

    for evt in events:
        result = _process_single_event(hh_id, evt, user_sub, user_name)
        if not result.get("success"):
            failed += 1
        results.append(result)

    return _response(200, {
        "results": results,
        "processed": len(results),
        "failed": failed,
    })


def _process_single_event(
    hh_id: str,
    evt: dict[str, Any],
    user_sub: str,
    user_name: str,
) -> dict[str, Any]:
    """Process a single delta event. Returns a result dict."""
    # Validate event structure
    if not isinstance(evt, dict):
        return {"success": False, "error": "VALIDATION_ERROR"}

    product_id = evt.get("productId")
    if not isinstance(product_id, str) or not product_id.strip():
        return {"success": False, "error": "VALIDATION_ERROR"}

    raw_delta = evt.get("delta")
    try:
        delta = validate_integer_range(raw_delta, "delta", min_val=-9999, max_val=9999)
        if delta == 0:
            return {
                "productId": product_id,
                "delta": 0,
                "success": False,
                "error": "VALIDATION_ERROR",
            }
    except Exception:
        return {
            "productId": product_id,
            "success": False,
            "error": "VALIDATION_ERROR",
        }

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
        code = exc.response["Error"]["Code"]
        if code == "ConditionalCheckFailedException":
            # Determine whether product doesn't exist or stock insufficient
            current_qty = _get_current_qty(hh_id, product_id)
            if current_qty is None:
                return {
                    "productId": product_id,
                    "delta": delta,
                    "success": False,
                    "error": "NOT_FOUND",
                }
            return {
                "productId": product_id,
                "delta": delta,
                "success": False,
                "error": "INSUFFICIENT_STOCK",
            }
        return {
            "productId": product_id,
            "delta": delta,
            "success": False,
            "error": "INTERNAL_ERROR",
        }

    updated_item = resp["Attributes"]
    new_qty: int = int(updated_item["currentQty"])

    # Write history record
    now = datetime.now(timezone.utc)
    timestamp = now.isoformat(timespec="seconds")
    hist_id = f"hist_{uuid.uuid4().hex[:12]}"
    ttl = int(time.time()) + _TTL_90_DAYS
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

    return {
        "productId": product_id,
        "delta": delta,
        "currentQty": new_qty,
        "success": True,
    }


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


def _json_default(obj: Any) -> Any:
    """JSON serializer for DynamoDB Decimal types."""
    from decimal import Decimal
    if isinstance(obj, Decimal):
        return int(obj) if obj == int(obj) else float(obj)
    raise TypeError(f"Object of type {type(obj).__name__} is not JSON serializable")


def _response(status_code: int, body: Any) -> dict[str, Any]:
    """Build an API Gateway proxy response."""
    return {
        "statusCode": status_code,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps(body, default=_json_default),
    }
