"""Households Lambda handler.

Supports:
- GET  /household       → get caller's household info + members (404 if none)
- POST /household       → create a new household + membership for caller
- POST /household/leave → leave current household (cleanup if last member)
"""

from __future__ import annotations

import json
import uuid
from datetime import datetime, timezone
from typing import Any

from boto3.dynamodb.conditions import Key

from shared.auth import get_user_sub
from shared.db import table
from shared.errors import (
    AlreadyInHouseholdError,
    AppError,
    NotFoundError,
    ValidationError,
    error_response,
    internal_error_response,
)
from shared.validators import validate_required_fields, validate_string_length


def handler(event: dict[str, Any], context: Any) -> dict[str, Any]:
    """Route incoming API Gateway proxy event to the appropriate action."""
    try:
        method = event["httpMethod"]
        resource = event.get("resource", "")

        user_sub = get_user_sub(event)

        if method == "GET" and resource == "/household":
            return _get_household(user_sub)

        if method == "POST" and resource == "/household":
            body = _parse_body(event)
            return _create_household(user_sub, body)

        if method == "POST" and resource == "/household/leave":
            return _leave_household(user_sub)

        return {
            "statusCode": 405,
            "headers": {"Content-Type": "application/json"},
            "body": json.dumps({"error": "METHOD_NOT_ALLOWED", "message": "Method not allowed"}),
        }

    except AppError as err:
        return error_response(err)
    except Exception:
        return internal_error_response()


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _parse_body(event: dict[str, Any]) -> dict[str, Any]:
    """Parse the JSON request body."""
    raw = event.get("body")
    if not raw:
        raise ValidationError("Request body is required")
    try:
        body = json.loads(raw)
    except (json.JSONDecodeError, TypeError) as exc:
        raise ValidationError("Invalid JSON in request body") from exc
    if not isinstance(body, dict):
        raise ValidationError("Request body must be a JSON object")
    return body


def _find_membership(user_sub: str) -> dict[str, Any] | None:
    """Look up the caller's membership item via GSI1.

    Returns the membership item if found, None otherwise.
    """
    resp = table.query(
        IndexName="GSI1",
        KeyConditionExpression="GSI1PK = :pk",
        ExpressionAttributeValues={":pk": f"USR#{user_sub}"},
    )
    items = resp.get("Items", [])
    return items[0] if items else None


# ---------------------------------------------------------------------------
# Actions
# ---------------------------------------------------------------------------


def _get_household(user_sub: str) -> dict[str, Any]:
    """GET /household — return household info + member list, or 404."""
    membership = _find_membership(user_sub)
    if not membership:
        raise NotFoundError("User is not a member of any household")

    hh_id = membership["GSI1SK"].removeprefix("HH#")

    # Fetch household #META
    meta_resp = table.get_item(Key={"PK": f"HH#{hh_id}", "SK": "#META"})
    meta = meta_resp.get("Item")
    if not meta:
        raise NotFoundError("Household not found")

    # Fetch all members (MBR# items)
    members_resp = table.query(
        KeyConditionExpression=Key("PK").eq(f"HH#{hh_id}") & Key("SK").begins_with("MBR#"),
    )
    member_items = members_resp.get("Items", [])

    members = [
        {
            "userId": m["SK"].removeprefix("MBR#"),
            "displayName": m.get("displayName", ""),
            "joinedAt": m.get("joinedAt", ""),
        }
        for m in member_items
    ]

    return {
        "statusCode": 200,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps({
            "householdId": hh_id,
            "name": meta.get("name", ""),
            "createdAt": meta.get("createdAt", ""),
            "members": members,
        }),
    }


def _create_household(user_sub: str, body: dict[str, Any]) -> dict[str, Any]:
    """POST /household — create household + membership. Reject if already in one."""
    # Check if user already belongs to a household
    existing = _find_membership(user_sub)
    if existing:
        raise AlreadyInHouseholdError(
            "User must leave current household before creating a new one"
        )

    validate_required_fields(body, ["name"])
    name = validate_string_length(body["name"], "name", min_len=1, max_len=100)

    hh_id = uuid.uuid4().hex
    now = datetime.now(timezone.utc).isoformat(timespec="seconds")

    # Create household #META item
    table.put_item(Item={
        "PK": f"HH#{hh_id}",
        "SK": "#META",
        "name": name,
        "createdAt": now,
    })

    # Create membership item for the calling user
    table.put_item(Item={
        "PK": f"HH#{hh_id}",
        "SK": f"MBR#{user_sub}",
        "GSI1PK": f"USR#{user_sub}",
        "GSI1SK": f"HH#{hh_id}",
        "displayName": name,
        "joinedAt": now,
    })

    return {
        "statusCode": 201,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps({
            "householdId": hh_id,
            "name": name,
            "createdAt": now,
        }),
    }


def _leave_household(user_sub: str) -> dict[str, Any]:
    """POST /household/leave — remove membership. Cleanup if last member."""
    membership = _find_membership(user_sub)
    if not membership:
        raise NotFoundError("User is not a member of any household")

    hh_id = membership["GSI1SK"].removeprefix("HH#")

    # Check how many members remain
    members_resp = table.query(
        KeyConditionExpression=Key("PK").eq(f"HH#{hh_id}") & Key("SK").begins_with("MBR#"),
    )
    member_items = members_resp.get("Items", [])

    # Delete the caller's membership
    table.delete_item(Key={"PK": f"HH#{hh_id}", "SK": f"MBR#{user_sub}"})

    # If this was the last member, delete the entire household
    if len(member_items) <= 1:
        _delete_household(hh_id)

    return {
        "statusCode": 200,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps({"left": hh_id}),
    }


def _delete_household(hh_id: str) -> None:
    """Delete all items with PK=HH#<hhId> (META, memberships, products, etc.)."""
    # Query all items in the household partition
    items_to_delete: list[dict[str, Any]] = []
    last_key = None

    while True:
        kwargs: dict[str, Any] = {
            "KeyConditionExpression": Key("PK").eq(f"HH#{hh_id}"),
        }
        if last_key:
            kwargs["ExclusiveStartKey"] = last_key

        resp = table.query(**kwargs)
        items_to_delete.extend(resp.get("Items", []))

        last_key = resp.get("LastEvaluatedKey")
        if not last_key:
            break

    # Batch delete all items
    with table.batch_writer() as batch:
        for item in items_to_delete:
            batch.delete_item(Key={"PK": item["PK"], "SK": item["SK"]})
