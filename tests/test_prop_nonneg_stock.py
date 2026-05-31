"""Property 3: Non-Negative Stock Invariant.

**Validates: Requirements 1.5, 12.2**

currentQty >= 0 at all times. A decrement that would make currentQty < 0 is
rejected with 409; currentQty remains unchanged.
"""

from __future__ import annotations

import json

from hypothesis import given, settings, HealthCheck
from hypothesis import strategies as st

from conftest import make_event, seed_category, seed_household, seed_product
from adjust.handler import handler


@settings(max_examples=50, suppress_health_check=[HealthCheck.function_scoped_fixture])
@given(
    initial_qty=st.integers(min_value=0, max_value=5),
    neg_delta=st.integers(min_value=-50, max_value=-1),
)
def test_nonneg_stock_invariant(ddb_table, initial_qty: int, neg_delta: int) -> None:
    """Negative deltas that exceed currentQty are rejected; qty never goes below 0."""
    hh_id = "hh-neg"
    user_sub = "user-neg"

    seed_household(ddb_table, hh_id, user_sub)
    seed_category(ddb_table, hh_id, "cat_n", "Cat")
    seed_product(ddb_table, hh_id, product_id="prod_neg", name="Neg",
                 category_id="cat_n", current_qty=initial_qty)

    event = make_event(
        method="POST", path="/adjust",
        body={"productId": "prod_neg", "delta": neg_delta},
        user_sub=user_sub,
    )
    resp = handler(event, None)

    if initial_qty + neg_delta >= 0:
        # Should succeed
        assert resp["statusCode"] == 200
        body = json.loads(resp["body"])
        assert body["currentQty"] == initial_qty + neg_delta
        assert body["currentQty"] >= 0
    else:
        # Should be rejected
        assert resp["statusCode"] == 409
        body = json.loads(resp["body"])
        assert body["error"] == "INSUFFICIENT_STOCK"
        # Verify currentQty unchanged
        item = ddb_table.get_item(
            Key={"PK": f"HH#{hh_id}", "SK": "PROD#prod_neg"}
        )["Item"]
        assert int(item["currentQty"]) == initial_qty

    # Cleanup
    from boto3.dynamodb.conditions import Key
    resp_q = ddb_table.query(KeyConditionExpression=Key("PK").eq(f"HH#{hh_id}"))
    for item in resp_q.get("Items", []):
        ddb_table.delete_item(Key={"PK": item["PK"], "SK": item["SK"]})
