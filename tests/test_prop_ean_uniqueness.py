"""Property 7: EAN Uniqueness Within Household.

**Validates: Requirements 4.7**

Within a single household, each EAN maps to exactly one product.
Duplicate EAN assignment always returns 409.
"""

from __future__ import annotations

import json

from hypothesis import given, settings, HealthCheck
from hypothesis import strategies as st

from conftest import make_event, seed_category, seed_household, seed_product
from eans.handler import handler

# Strategy: generate valid EAN-13 strings (13 digits)
ean_strategy = st.text(
    alphabet="0123456789", min_size=13, max_size=13
)


@settings(max_examples=50, suppress_health_check=[HealthCheck.function_scoped_fixture])
@given(ean=ean_strategy)
def test_duplicate_ean_within_household_returns_409(ddb_table, ean: str) -> None:
    """Adding the same EAN to two different products in one household returns 409."""
    hh_id = "hh-prop7"
    user_sub = "user-prop7"

    # Seed household + category + two products
    seed_household(ddb_table, hh_id, user_sub)
    seed_category(ddb_table, hh_id, "cat_p7", "Test")
    seed_product(ddb_table, hh_id, product_id="prod_x", name="Product X", category_id="cat_p7", eans=[])
    seed_product(ddb_table, hh_id, product_id="prod_y", name="Product Y", category_id="cat_p7", eans=[])

    # First assignment succeeds
    event1 = make_event(
        method="POST",
        resource="/products/{productId}/eans",
        path="/products/prod_x/eans",
        path_params={"productId": "prod_x"},
        body={"ean": ean},
        user_sub=user_sub,
    )
    resp1 = handler(event1, None)
    assert resp1["statusCode"] in (200, 201), f"First add failed: {resp1}"

    # Second assignment to a different product must return 409
    event2 = make_event(
        method="POST",
        resource="/products/{productId}/eans",
        path="/products/prod_y/eans",
        path_params={"productId": "prod_y"},
        body={"ean": ean},
        user_sub=user_sub,
    )
    resp2 = handler(event2, None)
    assert resp2["statusCode"] == 409
    body = json.loads(resp2["body"])
    assert body["error"] == "EAN_IN_USE"

    # Cleanup for next iteration
    ddb_table.delete_item(Key={"PK": f"HH#{hh_id}", "SK": f"EAN#{ean}"})
    ddb_table.delete_item(Key={"PK": f"HH#{hh_id}", "SK": "PROD#prod_x"})
    ddb_table.delete_item(Key={"PK": f"HH#{hh_id}", "SK": "PROD#prod_y"})
    ddb_table.delete_item(Key={"PK": f"HH#{hh_id}", "SK": "CAT#cat_p7"})
    ddb_table.delete_item(Key={"PK": f"HH#{hh_id}", "SK": f"MBR#{user_sub}"})
    ddb_table.delete_item(Key={"PK": f"HH#{hh_id}", "SK": "#META"})
