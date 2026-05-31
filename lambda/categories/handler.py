"""Categories Lambda handler.

Supports:
- GET    /categories              → list categories by sortOrder
- POST   /categories              → create category (next sortOrder, unique name)
- PUT    /categories/{categoryId} → update name/sortOrder
- DELETE /categories/{categoryId} → delete (requires reassignTo, blocks if last)
- PUT    /categories/reorder      → batch update sortOrder values
"""

from __future__ import annotations

import json
import uuid
from typing import Any

from boto3.dynamodb.conditions import Key

from shared.auth import get_user_sub, resolve_household
from shared.db import table
from shared.errors import (
    AppError,
    ConflictError,
    DuplicateNameError,
    NotFoundError,
    ValidationError,
    error_response,
    internal_error_response,
)
from shared.validators import validate_required_fields, validate_string_length


def _json_default(obj: Any) -> Any:
    """JSON serializer for DynamoDB Decimal types."""
    from decimal import Decimal
    if isinstance(obj, Decimal):
        return int(obj) if obj == int(obj) else float(obj)
    raise TypeError(f"Object of type {type(obj).__name__} is not JSON serializable")


def handler(event: dict[str, Any], context: Any) -> dict[str, Any]:
    """Route incoming API Gateway proxy event to the appropriate action."""
    try:
        method = event["httpMethod"]
        resource = event.get("resource", "")
        path_params = event.get("pathParameters") or {}

        user_sub = get_user_sub(event)
        hh_id = resolve_household(user_sub)

        if method == "GET" and resource == "/categories":
            return _list_categories(hh_id)

        if method == "POST" and resource == "/categories":
            body = _parse_body(event)
            return _create_category(hh_id, body)

        if method == "PUT" and resource == "/categories/reorder":
            body = _parse_body(event)
            return _reorder_categories(hh_id, body)

        if method == "PUT" and resource == "/categories/{categoryId}":
            cat_id = path_params.get("categoryId", "")
            body = _parse_body(event)
            return _update_category(hh_id, cat_id, body)

        if method == "DELETE" and resource == "/categories/{categoryId}":
            cat_id = path_params.get("categoryId", "")
            query_params = event.get("queryStringParameters") or {}
            return _delete_category(hh_id, cat_id, query_params)

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


def _get_all_categories(hh_id: str) -> list[dict[str, Any]]:
    """Query all category items for a household."""
    resp = table.query(
        KeyConditionExpression=Key("PK").eq(f"HH#{hh_id}") & Key("SK").begins_with("CAT#"),
    )
    return resp.get("Items", [])


def _check_duplicate_name(hh_id: str, name: str, *, exclude_cat_id: str | None = None) -> None:
    """Raise DuplicateNameError if a category with the same name exists (case-insensitive)."""
    categories = _get_all_categories(hh_id)
    lower_name = name.lower()
    for cat in categories:
        if cat["name"].lower() == lower_name:
            existing_id = cat["SK"].removeprefix("CAT#")
            if exclude_cat_id and existing_id == exclude_cat_id:
                continue
            raise DuplicateNameError(
                "A category with this name already exists",
                details={"name": name},
            )


# ---------------------------------------------------------------------------
# Actions
# ---------------------------------------------------------------------------


def _list_categories(hh_id: str) -> dict[str, Any]:
    """GET /categories — return all categories sorted by sortOrder."""
    categories = _get_all_categories(hh_id)
    categories.sort(key=lambda c: c.get("sortOrder", 0))

    items = [
        {
            "categoryId": cat["SK"].removeprefix("CAT#"),
            "name": cat["name"],
            "sortOrder": cat.get("sortOrder", 0),
        }
        for cat in categories
    ]

    return {
        "statusCode": 200,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps({"items": items}, default=_json_default),
    }


def _create_category(hh_id: str, body: dict[str, Any]) -> dict[str, Any]:
    """POST /categories — create a new category with the next sortOrder."""
    validate_required_fields(body, ["name"])
    name = validate_string_length(body["name"], "name", min_len=1, max_len=50)

    _check_duplicate_name(hh_id, name)

    # Determine next sortOrder
    categories = _get_all_categories(hh_id)
    max_sort = max((c.get("sortOrder", 0) for c in categories), default=0)
    next_sort = max_sort + 1

    cat_id = f"cat_{uuid.uuid4().hex}"

    item = {
        "PK": f"HH#{hh_id}",
        "SK": f"CAT#{cat_id}",
        "name": name,
        "sortOrder": next_sort,
    }
    table.put_item(Item=item)

    return {
        "statusCode": 201,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps({
            "categoryId": cat_id,
            "name": name,
            "sortOrder": next_sort,
        }),
    }


def _update_category(hh_id: str, cat_id: str, body: dict[str, Any]) -> dict[str, Any]:
    """PUT /categories/{categoryId} — update name and/or sortOrder."""
    if not cat_id:
        raise ValidationError("categoryId is required")

    # Verify category exists
    resp = table.get_item(Key={"PK": f"HH#{hh_id}", "SK": f"CAT#{cat_id}"})
    if "Item" not in resp:
        raise NotFoundError("Category not found", details={"categoryId": cat_id})

    update_exprs: list[str] = []
    attr_values: dict[str, Any] = {}

    if "name" in body:
        name = validate_string_length(body["name"], "name", min_len=1, max_len=50)
        _check_duplicate_name(hh_id, name, exclude_cat_id=cat_id)
        update_exprs.append("#n = :name")
        attr_values[":name"] = name

    if "sortOrder" in body:
        sort_order = body["sortOrder"]
        if not isinstance(sort_order, int) or isinstance(sort_order, bool) or sort_order < 1:
            raise ValidationError(
                "sortOrder must be a positive integer",
                details={"field": "sortOrder"},
            )
        update_exprs.append("sortOrder = :sortOrder")
        attr_values[":sortOrder"] = sort_order

    if not update_exprs:
        raise ValidationError("At least one of 'name' or 'sortOrder' must be provided")

    # Build update expression
    update_expr = "SET " + ", ".join(update_exprs)
    kwargs: dict[str, Any] = {
        "Key": {"PK": f"HH#{hh_id}", "SK": f"CAT#{cat_id}"},
        "UpdateExpression": update_expr,
        "ExpressionAttributeValues": attr_values,
        "ReturnValues": "ALL_NEW",
    }
    # 'name' is a DynamoDB reserved word
    if "#n = :name" in update_expr:
        kwargs["ExpressionAttributeNames"] = {"#n": "name"}

    result = table.update_item(**kwargs)
    updated = result["Attributes"]

    return {
        "statusCode": 200,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps({
            "categoryId": cat_id,
            "name": updated["name"],
            "sortOrder": updated.get("sortOrder", 0),
        }, default=_json_default),
    }


def _delete_category(
    hh_id: str, cat_id: str, query_params: dict[str, str]
) -> dict[str, Any]:
    """DELETE /categories/{categoryId} — delete with product reassignment."""
    if not cat_id:
        raise ValidationError("categoryId is required")

    reassign_to = query_params.get("reassignTo")
    if not reassign_to:
        raise ValidationError(
            "reassignTo query parameter is required",
            details={"field": "reassignTo"},
        )

    # Block if this is the last category
    categories = _get_all_categories(hh_id)
    if len(categories) <= 1:
        raise ConflictError(
            "Cannot delete the last category in the household",
            details={"categoryId": cat_id},
        )

    # Verify the category to delete exists
    cat_exists = any(c["SK"] == f"CAT#{cat_id}" for c in categories)
    if not cat_exists:
        raise NotFoundError("Category not found", details={"categoryId": cat_id})

    # Verify reassignTo category exists
    reassign_exists = any(c["SK"] == f"CAT#{reassign_to}" for c in categories)
    if not reassign_exists:
        raise NotFoundError(
            "Reassignment target category not found",
            details={"categoryId": reassign_to},
        )

    # Reassign products from deleted category to the target
    products_resp = table.query(
        KeyConditionExpression=Key("PK").eq(f"HH#{hh_id}") & Key("SK").begins_with("PROD#"),
    )
    products = products_resp.get("Items", [])

    for product in products:
        if product.get("categoryId") == cat_id:
            table.update_item(
                Key={"PK": product["PK"], "SK": product["SK"]},
                UpdateExpression="SET categoryId = :newCat",
                ExpressionAttributeValues={":newCat": reassign_to},
            )

    # Delete the category
    table.delete_item(Key={"PK": f"HH#{hh_id}", "SK": f"CAT#{cat_id}"})

    return {
        "statusCode": 200,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps({"deleted": cat_id, "reassignedTo": reassign_to}),
    }


def _reorder_categories(hh_id: str, body: dict[str, Any]) -> dict[str, Any]:
    """PUT /categories/reorder — batch update sortOrder values."""
    validate_required_fields(body, ["order"])
    order = body["order"]

    if not isinstance(order, list) or len(order) == 0:
        raise ValidationError(
            "order must be a non-empty array",
            details={"field": "order"},
        )

    # Validate each entry
    for entry in order:
        if not isinstance(entry, dict):
            raise ValidationError("Each order entry must be an object")
        if "categoryId" not in entry or "sortOrder" not in entry:
            raise ValidationError(
                "Each order entry must have categoryId and sortOrder",
                details={"fields": ["categoryId", "sortOrder"]},
            )
        if (
            not isinstance(entry["sortOrder"], int)
            or isinstance(entry["sortOrder"], bool)
            or entry["sortOrder"] < 1
        ):
            raise ValidationError(
                "sortOrder must be a positive integer",
                details={"field": "sortOrder", "categoryId": entry.get("categoryId")},
            )

    # Verify all categories exist
    categories = _get_all_categories(hh_id)
    existing_ids = {c["SK"].removeprefix("CAT#") for c in categories}

    for entry in order:
        if entry["categoryId"] not in existing_ids:
            raise NotFoundError(
                "Category not found",
                details={"categoryId": entry["categoryId"]},
            )

    # Batch update sortOrder
    for entry in order:
        table.update_item(
            Key={"PK": f"HH#{hh_id}", "SK": f"CAT#{entry['categoryId']}"},
            UpdateExpression="SET sortOrder = :so",
            ExpressionAttributeValues={":so": entry["sortOrder"]},
        )

    return {
        "statusCode": 200,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps({"reordered": len(order)}),
    }
