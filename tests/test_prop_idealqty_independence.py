"""Property 9: IdealQty Independence.

**Validates: Requirements 12.5, 5.3**

Mutating currentQty (via delta events) never alters idealQty, and mutating
idealQty (via product update) never alters currentQty.
"""

from __future__ import annotations

import json

from hypothesis import given, settings, HealthCheck
from hypothesis import strategies as st

from conftest import make_event, seed_category, seed_household, seed_product
from adjust.handler import handler as adjust_handler
from products.handler import handler as products_handler


@settings(max_examples=30, suppress_health_check=[HealthCheck.function_scoped_fixture])
@given(
    ideal_updates=st.lists(st.integers(min_value=1, max_value=9999), min_size=1, max_size=4),
    deltas=st.lists(st.integers(min_value=1, max_value=20), min_size=1, max_size=4),
)
def test_idealqty_independence(
    ddb_table, ideal_updates: list[int], deltas: list[int]
) -> None:
    """Interleaved ideal and current changes never affect each other."""
    hh_id = "hh-iq"
    user_sub = "user-iq"
    initial_ideal = 5
    initial_current = 2

    seed_household(ddb_table, hh_id, user_sub)
    seed_category(ddb_table, hh_id, "cat_iq", "Cat")
    seed_product(ddb_table, hh_id, product_id="prod_iq", name="IQ",
                 category_id="cat_iq", ideal_qty=initial_ideal,
                 current_qty=initial_current)

    running_current = initial_current
    running_ideal = initial_ideal

    # Interleave: update ideal then apply delta
    for ideal_val, delta in zip(ideal_updates, deltas):
        # Update idealQty
        event_up = make_event(
            method="PUT",
            path="/products/prod_iq",
            path_params={"productId": "prod_iq"},
            body={"idealQty": ideal_val},
            user_sub=user_sub,
        )
        resp_up = products_handler(event_up, None)
        assert resp_up["statusCode"] == 200
        body_up = json.loads(resp_up["body"])
        # idealQty updated; currentQty unchanged
        assert body_up["idealQty"] == ideal_val
        assert body_up["currentQty"] == running_current
        running_ideal = ideal_val

        # Apply delta to currentQty
        event_adj = make_event(
            method="POST", path="/adjust",
            body={"productId": "prod_iq", "delta": delta},
            user_sub=user_sub,
        )
        resp_adj = adjust_handler(event_adj, None)
        assert resp_adj["statusCode"] == 200
        body_adj = json.loads(resp_adj["body"])
        running_current += delta
        assert body_adj["currentQty"] == running_current

    # Final check: read product and confirm both fields independent
    item = ddb_table.get_item(
        Key={"PK": f"HH#{hh_id}", "SK": "PROD#prod_iq"}
    )["Item"]
    assert int(item["idealQty"]) == running_ideal
    assert int(item["currentQty"]) == running_current

    # Cleanup
    from boto3.dynamodb.conditions import Key
    resp = ddb_table.query(KeyConditionExpression=Key("PK").eq(f"HH#{hh_id}"))
    for i in resp.get("Items", []):
        ddb_table.delete_item(Key={"PK": i["PK"], "SK": i["SK"]})
