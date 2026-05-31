"""Unit tests for the EAN handler."""

from __future__ import annotations

import json

import pytest

from conftest import make_event, seed_category, seed_household, seed_product
from eans.handler import handler


@pytest.fixture()
def setup(ddb_table):
    """Seed household + category + product for EAN tests."""
    hh_id = "hh-001"
    user_sub = "user-123"
    seed_household(ddb_table, hh_id, user_sub)
    seed_category(ddb_table, hh_id, "cat_test1", "Dairy")
    seed_product(ddb_table, hh_id, product_id="prod_a", name="Milk", eans=[])
    return hh_id, user_sub, ddb_table


class TestAddEan:
    def test_add_ean_success(self, setup):
        _, user_sub, _ = setup
        event = make_event(
            method="POST",
            resource="/products/{productId}/eans",
            path="/products/prod_a/eans",
            path_params={"productId": "prod_a"},
            body={"ean": "5601234567890"},
            user_sub=user_sub,
        )
        resp = handler(event, None)
        assert resp["statusCode"] == 201
        body = json.loads(resp["body"])
        assert body["ean"] == "5601234567890"

    def test_add_invalid_ean_format(self, setup):
        _, user_sub, _ = setup
        event = make_event(
            method="POST",
            resource="/products/{productId}/eans",
            path="/products/prod_a/eans",
            path_params={"productId": "prod_a"},
            body={"ean": "abc"},
            user_sub=user_sub,
        )
        resp = handler(event, None)
        assert resp["statusCode"] == 400

    def test_add_duplicate_ean_returns_409(self, setup):
        hh_id, user_sub, tbl = setup
        # Add EAN mapping for prod_a
        tbl.put_item(Item={
            "PK": f"HH#{hh_id}",
            "SK": "EAN#5601234567890",
            "GSI1PK": "EAN#5601234567890",
            "GSI1SK": f"HH#{hh_id}",
            "productId": "prod_a",
        })
        # Update product eans list
        tbl.update_item(
            Key={"PK": f"HH#{hh_id}", "SK": "PROD#prod_a"},
            UpdateExpression="SET eans = :e",
            ExpressionAttributeValues={":e": ["5601234567890"]},
        )
        # Create a second product and try to assign the same EAN
        seed_product(tbl, hh_id, product_id="prod_b", name="Juice", eans=[])
        event = make_event(
            method="POST",
            resource="/products/{productId}/eans",
            path="/products/prod_b/eans",
            path_params={"productId": "prod_b"},
            body={"ean": "5601234567890"},
            user_sub=user_sub,
        )
        resp = handler(event, None)
        assert resp["statusCode"] == 409
        body = json.loads(resp["body"])
        assert body["error"] == "EAN_IN_USE"


class TestLookupEan:
    def test_lookup_not_found(self, setup):
        _, user_sub, _ = setup
        event = make_event(
            method="GET",
            resource="/eans/{ean}",
            path="/eans/5601234567890",
            path_params={"ean": "5601234567890"},
            user_sub=user_sub,
        )
        resp = handler(event, None)
        assert resp["statusCode"] == 404
