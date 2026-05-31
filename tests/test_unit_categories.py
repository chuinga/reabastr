"""Unit tests for the categories handler."""

from __future__ import annotations

import json

import pytest

from conftest import make_event, seed_category, seed_household, seed_product
from categories.handler import handler


@pytest.fixture()
def setup(ddb_table):
    """Seed household for category tests."""
    hh_id = "hh-001"
    user_sub = "user-123"
    seed_household(ddb_table, hh_id, user_sub)
    return hh_id, user_sub, ddb_table


class TestCreateCategory:
    def test_create_success(self, setup):
        _, user_sub, _ = setup
        event = make_event(
            method="POST",
            resource="/categories",
            path="/categories",
            body={"name": "Dairy"},
            user_sub=user_sub,
        )
        resp = handler(event, None)
        assert resp["statusCode"] == 201
        body = json.loads(resp["body"])
        assert body["name"] == "Dairy"
        assert body["sortOrder"] == 1

    def test_duplicate_name_rejected(self, setup):
        hh_id, user_sub, tbl = setup
        seed_category(tbl, hh_id, "cat_1", "Dairy")
        event = make_event(
            method="POST",
            resource="/categories",
            path="/categories",
            body={"name": "dairy"},
            user_sub=user_sub,
        )
        resp = handler(event, None)
        assert resp["statusCode"] == 409

    def test_missing_name(self, setup):
        _, user_sub, _ = setup
        event = make_event(
            method="POST",
            resource="/categories",
            path="/categories",
            body={},
            user_sub=user_sub,
        )
        resp = handler(event, None)
        assert resp["statusCode"] == 400


class TestDeleteCategory:
    def test_delete_last_category_blocked(self, setup):
        hh_id, user_sub, tbl = setup
        seed_category(tbl, hh_id, "cat_only", "Only")
        event = make_event(
            method="DELETE",
            resource="/categories/{categoryId}",
            path="/categories/cat_only",
            path_params={"categoryId": "cat_only"},
            query_params={"reassignTo": "cat_other"},
            user_sub=user_sub,
        )
        resp = handler(event, None)
        assert resp["statusCode"] == 409

    def test_delete_requires_reassign_param(self, setup):
        hh_id, user_sub, tbl = setup
        seed_category(tbl, hh_id, "cat_a", "A")
        seed_category(tbl, hh_id, "cat_b", "B")
        event = make_event(
            method="DELETE",
            resource="/categories/{categoryId}",
            path="/categories/cat_a",
            path_params={"categoryId": "cat_a"},
            query_params={},
            user_sub=user_sub,
        )
        resp = handler(event, None)
        assert resp["statusCode"] == 400


class TestListCategories:
    def test_list_sorted(self, setup):
        hh_id, user_sub, tbl = setup
        tbl.put_item(Item={"PK": f"HH#{hh_id}", "SK": "CAT#cat_b", "name": "B", "sortOrder": 2})
        tbl.put_item(Item={"PK": f"HH#{hh_id}", "SK": "CAT#cat_a", "name": "A", "sortOrder": 1})
        event = make_event(
            method="GET",
            resource="/categories",
            path="/categories",
            user_sub=user_sub,
        )
        resp = handler(event, None)
        assert resp["statusCode"] == 200
        items = json.loads(resp["body"])["items"]
        assert items[0]["name"] == "A"
        assert items[1]["name"] == "B"
