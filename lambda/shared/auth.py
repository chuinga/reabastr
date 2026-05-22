"""Authentication and household membership resolution."""

from __future__ import annotations

from typing import Any

from .db import table
from .errors import ForbiddenError, UnauthorizedError


def get_user_sub(event: dict[str, Any]) -> str:
    """Extract the Cognito user sub from an API Gateway proxy event."""
    try:
        return event["requestContext"]["authorizer"]["claims"]["sub"]
    except (KeyError, TypeError) as exc:
        raise UnauthorizedError("Missing or malformed authorization claims") from exc


def resolve_household(user_sub: str) -> str:
    """Look up the caller's household via GSI1.

    The Membership item has GSI1PK=USR#<sub> and GSI1SK=HH#<hhId>.
    Returns the household ID if found; raises ForbiddenError otherwise.
    """
    resp = table.query(
        IndexName="GSI1",
        KeyConditionExpression="GSI1PK = :pk",
        ExpressionAttributeValues={":pk": f"USR#{user_sub}"},
    )
    items = resp.get("Items", [])
    if not items:
        raise ForbiddenError("User is not a member of any household")
    return items[0]["GSI1SK"].removeprefix("HH#")
