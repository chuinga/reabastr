"""Unit tests for the products handler."""

from __future__ import annotations

import json

import pytest

from conftest import make_event, seed_category, seed_household, seed_product
from products.handler import handler


@pytest.fixture()
def setup(ddb_table):
    """Seed household + category for product tests."""
    hh_id = "hh-001"
    user_sub = "user-123"
    seed_household(ddb_table, hh_id, user_sub)
    seed_category(ddb_table, hh_id, "cat_test1", "Dairy")
    return hh_id, user_sub, ddb_table


class TestCreateProduct:
    def test_create_success(self, setup):
        hh_id, user_sub, _ = setup
        event = make_event(
            method="POST",
            path="/products",
            body={"name": "Milk 1L", "categoryId": "cat_test1", "idealQty": 3, "eans": []},
            user_sub=user_sub,
        )
        resp = handler(event, None)
        assert resp["statusCode"] == 201
        body = json.loads(resp["body"])
        assert body["name"] == "Milk 1L"
        assert body["currentQty"] == 0
        assert body["idealQty"] == 3

    def test_missing_name(self, setup):
        _, user_sub, _ = setup
        event = make_event(
            method="POST",
            path="/products",
            body={"categoryId": "cat_test1", "idealQty": 3},
            user_sub=user_sub,
        )
        resp = handler(event, None)
        assert resp["statusCode"] == 400

    def test_invalid_category(self, setup):
        _, user_sub, _ = setup
        event = make_event(
            method="POST",
            path="/products",
            body={"name": "Eggs", "categoryId": "cat_nonexist", "idealQty": 2},
            user_sub=user_sub,
        )
        resp = handler(event, None)
        assert resp["statusCode"] == 422

    def test_duplicate_name_rejected(self, setup):
        hh_id, user_sub, tbl = setup
        seed_product(tbl, hh_id, name="Milk", category_id="cat_test1")
        event = make_event(
            method="POST",
            path="/products",
            body={"name": "milk", "categoryId": "cat_test1", "idealQty": 1},
            user_sub=user_sub,
        )
        resp = handler(event, None)
        assert resp["statusCode"] == 409
        body = json.loads(resp["body"])
        assert body["error"] == "DUPLICATE_NAME"

    def test_ean_limit_exceeded(self, setup):
        _, user_sub, _ = setup
        eans = [f"{i:013d}" for i in range(21)]
        event = make_event(
            method="POST",
            path="/products",
            body={"name": "Big", "categoryId": "cat_test1", "idealQty": 1, "eans": eans},
            user_sub=user_sub,
        )
        resp = handler(event, None)
        assert resp["statusCode"] == 400


class TestListProducts:
    def test_list_empty(self, setup):
        _, user_sub, _ = setup
        event = make_event(method="GET", path="/products", user_sub=user_sub)
        resp = handler(event, None)
        assert resp["statusCode"] == 200
        assert json.loads(resp["body"]) == []

    def test_list_with_products(self, setup):
        hh_id, user_sub, tbl = setup
        seed_product(tbl, hh_id, product_id="prod_a", name="Milk")
        seed_product(tbl, hh_id, product_id="prod_b", name="Eggs")
        event = make_event(method="GET", path="/products", user_sub=user_sub)
        resp = handler(event, None)
        assert resp["statusCode"] == 200
        items = json.loads(resp["body"])
        assert len(items) == 2


class TestDeleteProduct:
    def test_delete_success(self, setup):
        hh_id, user_sub, tbl = setup
        pid = seed_product(tbl, hh_id, product_id="prod_del", name="ToDelete")
        event = make_event(
            method="DELETE",
            path="/products/prod_del",
            path_params={"productId": "prod_del"},
            user_sub=user_sub,
        )
        resp = handler(event, None)
        assert resp["statusCode"] == 204

    def test_delete_not_found(self, setup):
        _, user_sub, _ = setup
        event = make_event(
            method="DELETE",
            path="/products/prod_nope",
            path_params={"productId": "prod_nope"},
            user_sub=user_sub,
        )
        resp = handler(event, None)
        assert resp["statusCode"] == 404
