"""Property 10: History Completeness.

**Validates: Requirements 12.6, 12.7, 10.1**

Every successful currentQty mutation produces exactly 1 history record.
Rejected mutations produce 0 history records.
"""

from __future__ import annotations

import json

from boto3.dynamodb.conditions import Key
from hypothesis import given, settings, HealthCheck
from hypothesis import strategies as st

from conftest import make_event, seed_category, seed_household, seed_product
from adjust.handler import handler


@settings(max_examples=30, suppress_health_check=[HealthCheck.function_scoped_fixture])
@given(
    deltas=st.lists(st.integers(min_value=-5, max_value=10).filter(lambda x: x != 0), min_size=1, max_size=6),
)
def test_history_completeness(ddb_table, deltas: list[int]) -> None:
    """Each successful adjustment produces exactly 1 history record; rejects produce 0."""
    hh_id = "hh-hist"
    user_sub = "user-hist"
    initial_qty = 3

    seed_household(ddb_table, hh_id, user_sub)
    seed_category(ddb_table, hh_id, "cat_h", "Cat")
    seed_product(ddb_table, hh_id, product_id="prod_hist", name="Hist",
                 category_id="cat_h", current_qty=initial_qty)

    expected_history_count = 0
    current_qty = initial_qty

    for d in deltas:
        event = make_event(
            method="POST", path="/adjust",
            body={"productId": "prod_hist", "delta": d},
            user_sub=user_sub,
        )
        resp = handler(event, None)
        if resp["statusCode"] == 200:
            expected_history_count += 1
            current_qty += d
        else:
            # Reject — no history should be added
            pass

    # Count actual history records
    hist_resp = ddb_table.query(
        KeyConditionExpression=Key("PK").eq(f"HH#{hh_id}") & Key("SK").begins_with("HIST#"),
    )
    actual_count = len(hist_resp.get("Items", []))
    assert actual_count == expected_history_count

    # Cleanup
    resp_q = ddb_table.query(KeyConditionExpression=Key("PK").eq(f"HH#{hh_id}"))
    for item in resp_q.get("Items", []):
        ddb_table.delete_item(Key={"PK": item["PK"], "SK": item["SK"]})
