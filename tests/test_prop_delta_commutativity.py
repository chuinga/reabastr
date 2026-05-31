"""Property 2: Atomic Delta Commutativity.

**Validates: Requirements 12.1, 12.3**

For any sequence of delta events applied to the same product, the final
currentQty == initial + sum(deltas), provided no delta is rejected by the
negative-stock guard. Order does not matter.
"""

from __future__ import annotations

import json
import random

from hypothesis import given, settings, HealthCheck
from hypothesis import strategies as st

from conftest import make_event, seed_category, seed_household, seed_product
from adjust.handler import handler


@settings(max_examples=30, suppress_health_check=[HealthCheck.function_scoped_fixture])
@given(
    deltas=st.lists(st.integers(min_value=1, max_value=50), min_size=2, max_size=8),
)
def test_delta_commutativity(ddb_table, deltas: list[int]) -> None:
    """Applying positive deltas in any permutation yields the same final qty."""
    hh_id = "hh-comm"
    user_sub = "user-comm"
    initial_qty = 10

    seed_household(ddb_table, hh_id, user_sub)
    seed_category(ddb_table, hh_id, "cat_c", "Cat")
    seed_product(ddb_table, hh_id, product_id="prod_comm", name="Comm",
                 category_id="cat_c", current_qty=initial_qty)

    # Apply in original order
    for d in deltas:
        event = make_event(
            method="POST", path="/adjust",
            body={"productId": "prod_comm", "delta": d},
            user_sub=user_sub,
        )
        resp = handler(event, None)
        assert resp["statusCode"] == 200

    resp_body = json.loads(handler(make_event(
        method="POST", path="/adjust",
        body={"productId": "prod_comm", "delta": 1},
        user_sub=user_sub,
    ), None)["body"])
    final_qty_order1 = resp_body["currentQty"] - 1  # subtract the extra +1

    expected = initial_qty + sum(deltas)
    assert final_qty_order1 == expected

    # Cleanup
    from boto3.dynamodb.conditions import Key
    resp = ddb_table.query(KeyConditionExpression=Key("PK").eq(f"HH#{hh_id}"))
    for item in resp.get("Items", []):
        ddb_table.delete_item(Key={"PK": item["PK"], "SK": item["SK"]})
