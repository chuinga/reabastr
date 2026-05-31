"""Products Lambda handler — CRUD operations for household products."""

from __future__ import annotations

import json
import uuid
from datetime import datetime, timezone
from typing import Any

from shared.auth import get_user_sub, resolve_household
from shared.db import table
from shared.errors import (
    AppError,
    DuplicateNameError,
    EanInUseError,
    InvalidCategoryError,
    NotFoundError,
    ValidationError,
    error_response,
    internal_error_response,
)
from shared.validators import (
    validate_ean,
    validate_integer_range,
    validate_required_fields,
    validate_string_length,
)


def handler(event: dict[str, Any], context: Any) -> dict[str, Any]:
    """Route requests based on HTTP method and resource path."""
    try:
        user_sub = get_user_sub(event)
        hh_id = resolve_household(user_sub)

        method = event["httpMethod"]
        path_params = event.get("pathParameters") or {}
        product_id = path_params.get("productId")

        if method == "GET":
            return _list_products(hh_id)
        elif method == "POST":
            body = _parse_body(event)
            return _create_product(hh_id, body)
        elif method == "PUT":
            if not product_id:
                raise ValidationError("Missing productId path parameter")
            body = _parse_body(event)
            return _update_product(hh_id, product_id, body)
        elif method == "DELETE":
            if not product_id:
                raise ValidationError("Missing productId path parameter")
            return _delete_product(hh_id, product_id)
        else:
            return _response(405, {"error": "METHOD_NOT_ALLOWED", "message": f"Method {method} not allowed"})

    except AppError as exc:
        return error_response(exc)
    except Exception:
        return internal_error_response()


# ---------------------------------------------------------------------------
# Route handlers
# ---------------------------------------------------------------------------


def _list_products(hh_id: str) -> dict[str, Any]:
    """GET /products — list all products in the household."""
    resp = table.query(
        KeyConditionExpression="PK = :pk AND begins_with(SK, :prefix)",
        ExpressionAttributeValues={
            ":pk": f"HH#{hh_id}",
            ":prefix": "PROD#",
        },
    )
    products = [_format_product(item) for item in resp.get("Items", [])]
    return _response(200, products)


def _create_product(hh_id: str, body: dict[str, Any]) -> dict[str, Any]:
    """POST /products — create a product with validation."""
    validate_required_fields(body, ["name", "categoryId", "idealQty"])

    name = validate_string_length(body["name"], "name", min_len=1, max_len=100)
    category_id = body["categoryId"]
    ideal_qty = validate_integer_range(body["idealQty"], "idealQty", min_val=1, max_val=9999)
    eans = _validate_eans_list(body.get("eans", []))

    # Validate category exists in household
    _assert_category_exists(hh_id, category_id)

    # Validate name uniqueness (case-insensitive)
    _assert_name_unique(hh_id, name)

    # Validate EANs not already in use
    for ean in eans:
        _assert_ean_available(hh_id, ean)

    product_id = f"prod_{uuid.uuid4().hex[:12]}"
    now = datetime.now(timezone.utc).isoformat(timespec="seconds")

    item: dict[str, Any] = {
        "PK": f"HH#{hh_id}",
        "SK": f"PROD#{product_id}",
        "name": name,
        "categoryId": category_id,
        "idealQty": ideal_qty,
        "currentQty": 0,
        "eans": eans,
        "createdAt": now,
    }
    table.put_item(Item=item)

    # Write EAN mapping items
    for ean in eans:
        table.put_item(Item={
            "PK": f"HH#{hh_id}",
            "SK": f"EAN#{ean}",
            "GSI1PK": f"EAN#{ean}",
            "GSI1SK": f"HH#{hh_id}",
            "productId": product_id,
        })

    return _response(201, {
        "productId": product_id,
        "name": name,
        "categoryId": category_id,
        "idealQty": ideal_qty,
        "currentQty": 0,
        "eans": eans,
        "createdAt": now,
    })


def _update_product(hh_id: str, product_id: str, body: dict[str, Any]) -> dict[str, Any]:
    """PUT /products/{productId} — update name, idealQty, category; preserve currentQty."""
    # Fetch existing product
    existing = _get_product_item(hh_id, product_id)

    name = existing["name"]
    ideal_qty = existing["idealQty"]
    category_id = existing["categoryId"]

    if "name" in body:
        name = validate_string_length(body["name"], "name", min_len=1, max_len=100)
        # Only check uniqueness if name actually changed
        if name.lower() != existing["name"].lower():
            _assert_name_unique(hh_id, name)

    if "idealQty" in body:
        ideal_qty = validate_integer_range(body["idealQty"], "idealQty", min_val=1, max_val=9999)

    if "categoryId" in body:
        category_id = body["categoryId"]
        _assert_category_exists(hh_id, category_id)

    table.update_item(
        Key={"PK": f"HH#{hh_id}", "SK": f"PROD#{product_id}"},
        UpdateExpression="SET #n = :name, idealQty = :iq, categoryId = :cid",
        ExpressionAttributeNames={"#n": "name"},
        ExpressionAttributeValues={
            ":name": name,
            ":iq": ideal_qty,
            ":cid": category_id,
        },
    )

    return _response(200, {
        "productId": product_id,
        "name": name,
        "categoryId": category_id,
        "idealQty": ideal_qty,
        "currentQty": existing["currentQty"],
        "eans": existing.get("eans", []),
    })


def _delete_product(hh_id: str, product_id: str) -> dict[str, Any]:
    """DELETE /products/{productId} — remove product and all EAN mappings."""
    existing = _get_product_item(hh_id, product_id)

    # Delete all EAN mapping items for this product
    eans = existing.get("eans", [])
    for ean in eans:
        table.delete_item(Key={"PK": f"HH#{hh_id}", "SK": f"EAN#{ean}"})

    # Delete the product item itself
    table.delete_item(Key={"PK": f"HH#{hh_id}", "SK": f"PROD#{product_id}"})

    return _response(204, None)


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


def _get_product_item(hh_id: str, product_id: str) -> dict[str, Any]:
    """Fetch a product item or raise NotFoundError."""
    resp = table.get_item(Key={"PK": f"HH#{hh_id}", "SK": f"PROD#{product_id}"})
    item = resp.get("Item")
    if not item:
        raise NotFoundError(f"Product {product_id} not found")
    return item


def _assert_category_exists(hh_id: str, category_id: str) -> None:
    """Raise InvalidCategoryError if category doesn't exist in household."""
    resp = table.get_item(Key={"PK": f"HH#{hh_id}", "SK": f"CAT#{category_id}"})
    if not resp.get("Item"):
        raise InvalidCategoryError(f"Category {category_id} does not exist in this household")


def _assert_name_unique(hh_id: str, name: str) -> None:
    """Raise DuplicateNameError if a product with the same name exists (case-insensitive)."""
    resp = table.query(
        KeyConditionExpression="PK = :pk AND begins_with(SK, :prefix)",
        ExpressionAttributeValues={
            ":pk": f"HH#{hh_id}",
            ":prefix": "PROD#",
        },
    )
    for item in resp.get("Items", []):
        if item["name"].lower() == name.lower():
            raise DuplicateNameError(
                f"A product named '{name}' already exists in this household",
                details={"existingProductId": item["SK"].removeprefix("PROD#")},
            )


def _assert_ean_available(hh_id: str, ean: str) -> None:
    """Raise EanInUseError if the EAN is already mapped in this household."""
    resp = table.get_item(Key={"PK": f"HH#{hh_id}", "SK": f"EAN#{ean}"})
    if resp.get("Item"):
        existing_product_id = resp["Item"]["productId"]
        raise EanInUseError(
            f"EAN {ean} is already mapped to product {existing_product_id}",
            details={"ean": ean, "existingProductId": existing_product_id},
        )


def _validate_eans_list(eans: Any) -> list[str]:
    """Validate the eans field: optional list, max 20 items, each valid EAN."""
    if not eans:
        return []
    if not isinstance(eans, list):
        raise ValidationError("eans must be a list", details={"field": "eans"})
    if len(eans) > 20:
        raise ValidationError(
            "A product may have at most 20 EANs",
            details={"field": "eans", "max": 20},
        )
    validated: list[str] = []
    seen: set[str] = set()
    for i, ean in enumerate(eans):
        v = validate_ean(ean, field=f"eans[{i}]")
        if v in seen:
            raise ValidationError(
                f"Duplicate EAN in request: {v}",
                details={"field": "eans", "duplicate": v},
            )
        seen.add(v)
        validated.append(v)
    return validated


def _format_product(item: dict[str, Any]) -> dict[str, Any]:
    """Format a DynamoDB product item for the API response."""
    return {
        "productId": item["SK"].removeprefix("PROD#"),
        "name": item["name"],
        "categoryId": item["categoryId"],
        "idealQty": item["idealQty"],
        "currentQty": item.get("currentQty", 0),
        "eans": item.get("eans", []),
        "createdAt": item.get("createdAt"),
    }


def _json_default(obj: Any) -> Any:
    """JSON serializer for DynamoDB Decimal and other non-standard types."""
    from decimal import Decimal
    if isinstance(obj, Decimal):
        return int(obj) if obj == int(obj) else float(obj)
    raise TypeError(f"Object of type {type(obj).__name__} is not JSON serializable")


def _response(status_code: int, body: Any) -> dict[str, Any]:
    """Build an API Gateway proxy response."""
    resp: dict[str, Any] = {
        "statusCode": status_code,
        "headers": {"Content-Type": "application/json"},
    }
    if body is not None:
        resp["body"] = json.dumps(body, default=_json_default)
    else:
        resp["body"] = ""
    return resp
