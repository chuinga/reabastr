"""Shared test fixtures — moto DynamoDB table and helper factories."""

from __future__ import annotations

import json
import os
import uuid
from datetime import datetime, timezone
from typing import Any

# Set environment variables BEFORE any handler imports
os.environ.setdefault("TABLE_NAME", "reabastr-main")
os.environ.setdefault("AWS_DEFAULT_REGION", "eu-west-1")
os.environ.setdefault("AWS_ACCESS_KEY_ID", "testing")
os.environ.setdefault("AWS_SECRET_ACCESS_KEY", "testing")
os.environ.setdefault("AWS_SECURITY_TOKEN", "testing")
os.environ.setdefault("AWS_SESSION_TOKEN", "testing")

import boto3
import pytest
from moto import mock_aws


class _ConditionAwareTable:
    """Proxy around a moto DynamoDB Table that handles arithmetic ConditionExpressions.

    moto cannot parse expressions like `currentQty + :delta >= :zero`.
    This proxy intercepts update_item calls with such conditions, evaluates
    the condition in Python, and either proceeds (without the condition) or
    raises ConditionalCheckFailedException.
    """

    def __init__(self, real_table):
        self._table = real_table

    def __getattr__(self, name: str):
        return getattr(self._table, name)

    def update_item(self, **kwargs):
        condition = kwargs.get("ConditionExpression", "")
        expr_values = kwargs.get("ExpressionAttributeValues", {})

        # Detect the negative-stock guard pattern used by adjust/sync handlers
        if "currentQty + :delta >= :zero" in condition:
            from botocore.exceptions import ClientError

            key = kwargs["Key"]
            delta = int(expr_values.get(":delta", 0))

            # Fetch current item to evaluate condition
            resp = self._table.get_item(Key=key)
            item = resp.get("Item")

            # Check attribute_exists(SK) — item must exist
            if not item:
                raise ClientError(
                    {"Error": {"Code": "ConditionalCheckFailedException", "Message": "Condition not met"}},
                    "UpdateItem",
                )

            current_qty = int(item.get("currentQty", 0))

            # Evaluate: (currentQty + delta >= 0 OR delta > 0)
            if not (current_qty + delta >= 0 or delta > 0):
                raise ClientError(
                    {"Error": {"Code": "ConditionalCheckFailedException", "Message": "Condition not met"}},
                    "UpdateItem",
                )

            # Condition passes — execute without the condition expression
            # Also remove :zero from ExpressionAttributeValues since it's only used in the condition
            clean_kwargs = {k: v for k, v in kwargs.items() if k != "ConditionExpression"}
            if "ExpressionAttributeValues" in clean_kwargs:
                clean_kwargs["ExpressionAttributeValues"] = {
                    k: v for k, v in clean_kwargs["ExpressionAttributeValues"].items()
                    if k != ":zero"
                }
            return self._table.update_item(**clean_kwargs)

        # For all other update_item calls, pass through directly
        return self._table.update_item(**kwargs)


@pytest.fixture()
def ddb_table(monkeypatch: pytest.MonkeyPatch):
    """Create a moto DynamoDB table matching the production schema."""
    with mock_aws():
        dynamodb = boto3.resource("dynamodb", region_name="eu-west-1")
        tbl = dynamodb.create_table(
            TableName="reabastr-main",
            KeySchema=[
                {"AttributeName": "PK", "KeyType": "HASH"},
                {"AttributeName": "SK", "KeyType": "RANGE"},
            ],
            AttributeDefinitions=[
                {"AttributeName": "PK", "AttributeType": "S"},
                {"AttributeName": "SK", "AttributeType": "S"},
                {"AttributeName": "GSI1PK", "AttributeType": "S"},
                {"AttributeName": "GSI1SK", "AttributeType": "S"},
            ],
            GlobalSecondaryIndexes=[
                {
                    "IndexName": "GSI1",
                    "KeySchema": [
                        {"AttributeName": "GSI1PK", "KeyType": "HASH"},
                        {"AttributeName": "GSI1SK", "KeyType": "RANGE"},
                    ],
                    "Projection": {"ProjectionType": "ALL"},
                }
            ],
            BillingMode="PAY_PER_REQUEST",
        )
        tbl.meta.client.get_waiter("table_exists").wait(TableName="reabastr-main")

        # Wrap table with condition-aware proxy (moto can't parse arithmetic conditions)
        wrapped = _ConditionAwareTable(tbl)

        # Patch shared.db.table AND every handler module that imported it
        import shared.db as db_module
        monkeypatch.setattr(db_module, "table", wrapped)

        import shared.auth
        import products.handler
        import adjust.handler
        import categories.handler
        import eans.handler
        import households.handler
        import share_code.handler
        import history.handler
        import sync.handler

        for mod in [
            shared.auth,
            products.handler,
            adjust.handler,
            categories.handler,
            eans.handler,
            households.handler,
            share_code.handler,
            history.handler,
            sync.handler,
        ]:
            if hasattr(mod, "table"):
                monkeypatch.setattr(mod, "table", wrapped)

        yield tbl


# ---------------------------------------------------------------------------
# Helper factories
# ---------------------------------------------------------------------------


def make_event(
    method: str = "GET",
    path: str = "/",
    resource: str | None = None,
    body: dict[str, Any] | None = None,
    path_params: dict[str, str] | None = None,
    query_params: dict[str, str] | None = None,
    user_sub: str = "user-123",
    claims_extra: dict[str, str] | None = None,
) -> dict[str, Any]:
    """Build a minimal API Gateway proxy event."""
    claims: dict[str, str] = {"sub": user_sub}
    if claims_extra:
        claims.update(claims_extra)

    event: dict[str, Any] = {
        "httpMethod": method,
        "path": path,
        "resource": resource or path,
        "pathParameters": path_params,
        "queryStringParameters": query_params,
        "requestContext": {"authorizer": {"claims": claims}},
        "body": json.dumps(body) if body else None,
    }
    return event


def seed_household(table, hh_id: str, user_sub: str, name: str = "Test Household") -> None:
    """Seed a household with META + membership items."""
    now = datetime.now(timezone.utc).isoformat(timespec="seconds")
    table.put_item(Item={
        "PK": f"HH#{hh_id}",
        "SK": "#META",
        "name": name,
        "createdAt": now,
    })
    table.put_item(Item={
        "PK": f"HH#{hh_id}",
        "SK": f"MBR#{user_sub}",
        "GSI1PK": f"USR#{user_sub}",
        "GSI1SK": f"HH#{hh_id}",
        "displayName": "Test User",
        "joinedAt": now,
    })


def seed_category(table, hh_id: str, cat_id: str = "cat_test1", name: str = "Dairy") -> str:
    """Seed a category item. Returns the cat_id."""
    table.put_item(Item={
        "PK": f"HH#{hh_id}",
        "SK": f"CAT#{cat_id}",
        "name": name,
        "sortOrder": 1,
    })
    return cat_id


def seed_product(
    table,
    hh_id: str,
    product_id: str | None = None,
    name: str = "Milk",
    category_id: str = "cat_test1",
    ideal_qty: int = 3,
    current_qty: int = 2,
    eans: list[str] | None = None,
) -> str:
    """Seed a product item. Returns the product_id."""
    if product_id is None:
        product_id = f"prod_{uuid.uuid4().hex[:12]}"
    table.put_item(Item={
        "PK": f"HH#{hh_id}",
        "SK": f"PROD#{product_id}",
        "name": name,
        "categoryId": category_id,
        "idealQty": ideal_qty,
        "currentQty": current_qty,
        "eans": eans or [],
        "createdAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    })
    return product_id
