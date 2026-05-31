"""EAN management endpoints.

POST   /products/{productId}/eans   — Add EAN to product
DELETE /products/{productId}/eans/{ean} — Remove EAN mapping
GET    /eans/{ean}                   — Lookup product by EAN (household-scoped)
"""

from __future__ import annotations

import json
from typing import Any

from shared.auth import get_user_sub, resolve_household
from shared.db import table
from shared.errors import (
    AppError,
    EanInUseError,
    NotFoundError,
    ValidationError,
    error_response,
    internal_error_response,
)
from shared.validators import validate_ean, validate_required_fields


_MAX_EANS_PER_PRODUCT = 20


def handler(event: dict[str, Any], context: Any) -> dict[str, Any]:
    """Lambda entry point — routes by httpMethod + resource path."""
    try:
        user_sub = get_user_sub(event)
        hh_id = resolve_household(user_sub)

        method = event["httpMethod"]
        resource = event.get("resource", "")

        if resource == "/products/{productId}/eans" and method == "POST":
            return _add_ean(event, hh_id)
        elif resource == "/products/{productId}/eans/{ean}" and method == "DELETE":
            return _remove_ean(event, hh_id)
        elif resource == "/eans/{ean}" and method == "GET":
            return _lookup_ean(event, hh_id)
        else:
            return _json_response(404, {"error": "NOT_FOUND", "message": "Route not found"})

    except AppError as exc:
        return error_response(exc)
    except Exception:
        return internal_error_response()


def _add_ean(event: dict[str, Any], hh_id: str) -> dict[str, Any]:
    """POST /products/{productId}/eans — add an EAN to a product."""
    product_id = event["pathParameters"]["productId"]

    body = json.loads(event.get("body") or "{}")
    validate_required_fields(body, ["ean"])
    ean = validate_ean(body["ean"])

    # Verify product exists and belongs to household
    product = _get_product(hh_id, product_id)

    # Enforce max 20 EANs per product
    existing_eans: list[str] = product.get("eans", [])
    if len(existing_eans) >= _MAX_EANS_PER_PRODUCT:
        raise ValidationError(
            f"A product can have at most {_MAX_EANS_PER_PRODUCT} EANs",
            details={"limit": _MAX_EANS_PER_PRODUCT, "current": len(existing_eans)},
        )

    # Check if EAN already mapped to this product (idempotent)
    if ean in existing_eans:
        return _json_response(200, {"productId": product_id, "ean": ean})

    # Check uniqueness within household via GSI1
    existing = _find_ean_in_household(ean, hh_id)
    if existing:
        raise EanInUseError(
            "EAN is already mapped to another product in this household",
            details={
                "ean": ean,
                "existingProductId": existing["productId"],
            },
        )

    # Write EAN mapping item
    table.put_item(
        Item={
            "PK": f"HH#{hh_id}",
            "SK": f"EAN#{ean}",
            "GSI1PK": f"EAN#{ean}",
            "GSI1SK": f"HH#{hh_id}",
            "productId": product_id,
        }
    )

    # Append EAN to product's eans[] list
    table.update_item(
        Key={"PK": f"HH#{hh_id}", "SK": f"PROD#{product_id}"},
        UpdateExpression="SET eans = list_append(if_not_exists(eans, :empty), :new_ean)",
        ExpressionAttributeValues={
            ":empty": [],
            ":new_ean": [ean],
        },
    )

    return _json_response(201, {"productId": product_id, "ean": ean})


def _remove_ean(event: dict[str, Any], hh_id: str) -> dict[str, Any]:
    """DELETE /products/{productId}/eans/{ean} — remove an EAN mapping."""
    product_id = event["pathParameters"]["productId"]
    ean = event["pathParameters"]["ean"]

    # Validate EAN format
    validate_ean(ean)

    # Verify product exists and belongs to household
    product = _get_product(hh_id, product_id)

    # Check the EAN is actually mapped to this product
    existing_eans: list[str] = product.get("eans", [])
    if ean not in existing_eans:
        raise NotFoundError(
            "EAN is not mapped to this product",
            details={"ean": ean, "productId": product_id},
        )

    # Delete EAN mapping item
    table.delete_item(
        Key={"PK": f"HH#{hh_id}", "SK": f"EAN#{ean}"}
    )

    # Remove EAN from product's eans[] list
    ean_index = existing_eans.index(ean)
    table.update_item(
        Key={"PK": f"HH#{hh_id}", "SK": f"PROD#{product_id}"},
        UpdateExpression=f"REMOVE eans[{ean_index}]",
    )

    return _json_response(200, {"productId": product_id, "ean": ean, "removed": True})


def _lookup_ean(event: dict[str, Any], hh_id: str) -> dict[str, Any]:
    """GET /eans/{ean} — lookup product by EAN, scoped to caller's household."""
    ean = event["pathParameters"]["ean"]

    # Validate EAN format
    validate_ean(ean)

    # Query GSI1 for this EAN, filter to caller's household
    mapping = _find_ean_in_household(ean, hh_id)
    if not mapping:
        raise NotFoundError(
            "EAN not found in your household",
            details={"ean": ean},
        )

    # Fetch the full product for the response
    product_id = mapping["productId"]
    product = _get_product(hh_id, product_id)

    return _json_response(200, {
        "productId": product_id,
        "name": product.get("name"),
        "categoryId": product.get("categoryId"),
        "idealQty": product.get("idealQty"),
        "currentQty": product.get("currentQty"),
        "eans": product.get("eans", []),
    })


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _get_product(hh_id: str, product_id: str) -> dict[str, Any]:
    """Fetch a product item; raises NotFoundError if missing."""
    resp = table.get_item(Key={"PK": f"HH#{hh_id}", "SK": f"PROD#{product_id}"})
    item = resp.get("Item")
    if not item:
        raise NotFoundError(
            "Product not found",
            details={"productId": product_id},
        )
    return item


def _find_ean_in_household(ean: str, hh_id: str) -> dict[str, Any] | None:
    """Query GSI1 for an EAN mapping within the given household."""
    resp = table.query(
        IndexName="GSI1",
        KeyConditionExpression="GSI1PK = :pk AND GSI1SK = :sk",
        ExpressionAttributeValues={
            ":pk": f"EAN#{ean}",
            ":sk": f"HH#{hh_id}",
        },
    )
    items = resp.get("Items", [])
    return items[0] if items else None


def _json_default(obj: Any) -> Any:
    """JSON serializer for DynamoDB Decimal types."""
    from decimal import Decimal
    if isinstance(obj, Decimal):
        return int(obj) if obj == int(obj) else float(obj)
    raise TypeError(f"Object of type {type(obj).__name__} is not JSON serializable")


def _json_response(status_code: int, body: dict[str, Any]) -> dict[str, Any]:
    """Build an API Gateway proxy response."""
    return {
        "statusCode": status_code,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps(body, default=_json_default),
    }
