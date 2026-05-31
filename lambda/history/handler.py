"""History Lambda handler — paginated reverse-chronological history."""

from __future__ import annotations

import base64
import json
from typing import Any

from shared.auth import get_user_sub, resolve_household
from shared.db import table
from shared.errors import (
    AppError,
    ValidationError,
    error_response,
    internal_error_response,
)

_DEFAULT_LIMIT = 50
_MAX_LIMIT = 100


def handler(event: dict[str, Any], context: Any) -> dict[str, Any]:
    """Route requests based on HTTP method."""
    try:
        user_sub = get_user_sub(event)
        hh_id = resolve_household(user_sub)

        method = event["httpMethod"]

        if method == "GET":
            params = event.get("queryStringParameters") or {}
            return _list_history(hh_id, params)
        else:
            return _response(405, {"error": "METHOD_NOT_ALLOWED", "message": f"Method {method} not allowed"})

    except AppError as exc:
        return error_response(exc)
    except Exception:
        return internal_error_response()


# ---------------------------------------------------------------------------
# Route handler
# ---------------------------------------------------------------------------


def _list_history(hh_id: str, params: dict[str, str]) -> dict[str, Any]:
    """GET /history — paginated history in reverse chronological order."""
    limit = _parse_limit(params.get("limit"))
    exclusive_start_key = _decode_cursor(params.get("cursor"))

    query_kwargs: dict[str, Any] = {
        "KeyConditionExpression": "PK = :pk AND begins_with(SK, :prefix)",
        "ExpressionAttributeValues": {
            ":pk": f"HH#{hh_id}",
            ":prefix": "HIST#",
        },
        "ScanIndexForward": False,
        "Limit": limit,
    }

    if exclusive_start_key is not None:
        query_kwargs["ExclusiveStartKey"] = exclusive_start_key

    resp = table.query(**query_kwargs)

    items = [_format_history_item(item) for item in resp.get("Items", [])]

    # Build next cursor from LastEvaluatedKey if present
    next_cursor: str | None = None
    last_evaluated_key = resp.get("LastEvaluatedKey")
    if last_evaluated_key:
        next_cursor = _encode_cursor(last_evaluated_key)

    return _response(200, {"items": items, "cursor": next_cursor})


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _parse_limit(raw: str | None) -> int:
    """Parse and clamp the limit query parameter."""
    if raw is None:
        return _DEFAULT_LIMIT
    try:
        limit = int(raw)
    except (ValueError, TypeError) as exc:
        raise ValidationError("limit must be an integer") from exc
    if limit < 1:
        raise ValidationError("limit must be at least 1")
    return min(limit, _MAX_LIMIT)


def _decode_cursor(raw: str | None) -> dict[str, Any] | None:
    """Decode a base64-encoded cursor into a DynamoDB ExclusiveStartKey."""
    if not raw:
        return None
    try:
        decoded = base64.b64decode(raw)
        return json.loads(decoded)
    except (ValueError, json.JSONDecodeError) as exc:
        raise ValidationError("Invalid cursor") from exc


def _encode_cursor(last_evaluated_key: dict[str, Any]) -> str:
    """Encode a DynamoDB LastEvaluatedKey as a base64 cursor string."""
    raw = json.dumps(last_evaluated_key, separators=(",", ":"))
    return base64.b64encode(raw.encode()).decode()


def _format_history_item(item: dict[str, Any]) -> dict[str, Any]:
    """Format a DynamoDB history item for the API response.

    SK format: HIST#<timestamp>#<histId>
    """
    sk = item["SK"]
    # Remove HIST# prefix, then split on # to get timestamp and histId
    parts = sk.removeprefix("HIST#").split("#", 1)
    timestamp = parts[0] if len(parts) > 0 else ""
    history_id = parts[1] if len(parts) > 1 else ""

    return {
        "historyId": history_id,
        "productName": item.get("productName", ""),
        "delta": item.get("delta", 0),
        "userName": item.get("userName", ""),
        "userId": item.get("userId", ""),
        "timestamp": timestamp,
    }


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
