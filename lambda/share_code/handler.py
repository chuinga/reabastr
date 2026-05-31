"""Share Code Lambda handler — generate and redeem household invite codes."""

from __future__ import annotations

import json
import random
import string
import time
from datetime import datetime, timezone, timedelta
from typing import Any

from boto3.dynamodb.conditions import Key
from botocore.exceptions import ClientError

from shared.auth import get_user_sub, resolve_household
from shared.db import table
from shared.errors import (
    AlreadyInHouseholdError,
    AppError,
    ForbiddenError,
    NotFoundError,
    ShareCodeExpiredError,
    ShareCodeRedeemedError,
    ValidationError,
    error_response,
    internal_error_response,
)
from shared.validators import validate_required_fields

_TTL_24H = 24 * 60 * 60
_CODE_CHARS = string.ascii_uppercase + string.digits


def handler(event: dict[str, Any], context: Any) -> dict[str, Any]:
    """Route POST /household/share-code and POST /household/join."""
    try:
        path = event.get("path", "")
        method = event.get("httpMethod", "")

        if method == "POST" and path == "/household/share-code":
            return _handle_generate_code(event)
        elif method == "POST" and path == "/household/join":
            return _handle_join(event)
        else:
            return _response(404, {"error": "NOT_FOUND", "message": "Route not found"})

    except AppError as exc:
        return error_response(exc)
    except Exception:
        return internal_error_response()


# ---------------------------------------------------------------------------
# POST /household/share-code
# ---------------------------------------------------------------------------


def _handle_generate_code(event: dict[str, Any]) -> dict[str, Any]:
    """Generate a share code for the caller's household (24h TTL, replaces active)."""
    user_sub = get_user_sub(event)
    hh_id = resolve_household(user_sub)

    # Invalidate any existing active (non-expired, non-redeemed) share codes
    _invalidate_active_codes(hh_id)

    # Generate new code
    code = _generate_code()
    now = datetime.now(timezone.utc)
    expires_at = now + timedelta(hours=24)
    ttl = int(time.time()) + _TTL_24H

    table.put_item(Item={
        "PK": f"HH#{hh_id}",
        "SK": f"SHARE#{code}",
        "GSI1PK": f"SHARE#{code}",
        "GSI1SK": f"HH#{hh_id}",
        "expiresAt": expires_at.isoformat(timespec="seconds").replace("+00:00", "Z"),
        "redeemed": False,
        "ttl": ttl,
    })

    return _response(201, {
        "code": code,
        "expiresAt": expires_at.isoformat(timespec="seconds").replace("+00:00", "Z"),
    })


def _invalidate_active_codes(hh_id: str) -> None:
    """Delete active (non-expired, non-redeemed) share codes for a household."""
    now_iso = datetime.now(timezone.utc).isoformat(timespec="seconds")

    resp = table.query(
        KeyConditionExpression=Key("PK").eq(f"HH#{hh_id}") & Key("SK").begins_with("SHARE#"),
    )

    for item in resp.get("Items", []):
        if item.get("redeemed"):
            continue
        if item.get("expiresAt", "") <= now_iso:
            continue
        # Active code — delete it
        table.delete_item(Key={"PK": item["PK"], "SK": item["SK"]})


def _generate_code() -> str:
    """Generate a human-readable code in XXXX-XXXX-XXXX format."""
    parts = [
        "".join(random.choices(_CODE_CHARS, k=4))
        for _ in range(3)
    ]
    return "-".join(parts)


# ---------------------------------------------------------------------------
# POST /household/join
# ---------------------------------------------------------------------------


def _handle_join(event: dict[str, Any]) -> dict[str, Any]:
    """Redeem a share code to join a household."""
    user_sub = get_user_sub(event)

    # Check user is NOT already in a household
    if _user_has_household(user_sub):
        raise AlreadyInHouseholdError(
            "User is already a member of a household"
        )

    body = _parse_body(event)
    validate_required_fields(body, ["code"])
    code = body["code"]
    if not isinstance(code, str) or not code.strip():
        raise ValidationError(
            "code must be a non-empty string",
            details={"field": "code"},
        )
    code = code.strip().upper()

    # Look up code via GSI1
    resp = table.query(
        IndexName="GSI1",
        KeyConditionExpression=Key("GSI1PK").eq(f"SHARE#{code}"),
    )
    items = resp.get("Items", [])
    if not items:
        raise NotFoundError("Share code not found")

    share_item = items[0]

    # Validate not redeemed
    if share_item.get("redeemed"):
        raise ShareCodeRedeemedError("This share code has already been used")

    # Validate not expired
    expires_at_str = share_item.get("expiresAt", "")
    now = datetime.now(timezone.utc)
    expires_at = datetime.fromisoformat(expires_at_str.replace("Z", "+00:00"))
    if now >= expires_at:
        raise ShareCodeExpiredError("This share code has expired")

    # Extract household ID from the share code item
    hh_id = share_item["GSI1SK"].removeprefix("HH#")

    # Resolve display name from Cognito claims
    display_name = _get_display_name(event)

    # Create membership item
    joined_at = now.isoformat(timespec="seconds").replace("+00:00", "Z")
    table.put_item(Item={
        "PK": f"HH#{hh_id}",
        "SK": f"MBR#{user_sub}",
        "GSI1PK": f"USR#{user_sub}",
        "GSI1SK": f"HH#{hh_id}",
        "displayName": display_name,
        "joinedAt": joined_at,
    })

    # Mark code as redeemed
    table.update_item(
        Key={"PK": share_item["PK"], "SK": share_item["SK"]},
        UpdateExpression="SET redeemed = :t",
        ExpressionAttributeValues={":t": True},
    )

    return _response(200, {
        "householdId": hh_id,
        "joinedAt": joined_at,
    })


def _user_has_household(user_sub: str) -> bool:
    """Check if user already belongs to a household via GSI1."""
    resp = table.query(
        IndexName="GSI1",
        KeyConditionExpression=Key("GSI1PK").eq(f"USR#{user_sub}"),
        Limit=1,
    )
    return len(resp.get("Items", [])) > 0


def _get_display_name(event: dict[str, Any]) -> str:
    """Extract display name from Cognito claims (name or email)."""
    claims = event.get("requestContext", {}).get("authorizer", {}).get("claims", {})
    name = claims.get("name")
    if name and name.strip():
        return name.strip()
    email = claims.get("email", "")
    if email:
        return email
    return "Unknown"


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


def _response(status_code: int, body: Any) -> dict[str, Any]:
    """Build an API Gateway proxy response."""
    from decimal import Decimal

    def _default(obj: Any) -> Any:
        if isinstance(obj, Decimal):
            return int(obj) if obj == int(obj) else float(obj)
        raise TypeError(f"Object of type {type(obj).__name__} is not JSON serializable")

    return {
        "statusCode": status_code,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps(body, default=_default),
    }
