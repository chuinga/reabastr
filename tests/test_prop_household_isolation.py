"""Property 6: Household Isolation.

**Validates: Requirements 9.9, 2.6, 2.7**

No API operation allows a user to read or mutate data belonging to a household
they are not a member of. Cross-household access returns 403.
"""

from __future__ import annotations

import json

from hypothesis import given, settings, HealthCheck
from hypothesis import strategies as st

from conftest import make_event, seed_category, seed_household, seed_product
from products.handler import handler as products_handler
from adjust.handler import handler as adjust_handler
from categories.handler import handler as categories_handler


@settings(max_examples=20, suppress_health_check=[HealthCheck.function_scoped_fixture])
@given(
    outsider_sub=st.text(
        alphabet="abcdefghijklmnopqrstuvwxyz0123456789", min_size=8, max_size=12
    ).map(lambda s: f"outsider-{s}"),
)
def test_household_isolation(ddb_table, outsider_sub: str) -> None:
    """Users not in a household get 403 on all operations."""
    hh_id = "hh-iso"
    member_sub = "user-member"

    seed_household(ddb_table, hh_id, member_sub)
    seed_category(ddb_table, hh_id, "cat_iso", "Cat")
    seed_product(ddb_table, hh_id, product_id="prod_iso", name="Secret",
                 category_id="cat_iso")

    # outsider_sub has no membership — all operations should return 403

    # GET products
    event_list = make_event(method="GET", path="/products", user_sub=outsider_sub)
    resp = products_handler(event_list, None)
    assert resp["statusCode"] == 403

    # POST adjust
    event_adj = make_event(
        method="POST", path="/adjust",
        body={"productId": "prod_iso", "delta": 1},
        user_sub=outsider_sub,
    )
    resp = adjust_handler(event_adj, None)
    assert resp["statusCode"] == 403

    # GET categories
    event_cat = make_event(
        method="GET", resource="/categories", path="/categories",
        user_sub=outsider_sub,
    )
    resp = categories_handler(event_cat, None)
    assert resp["statusCode"] == 403

    # Cleanup: remove outsider's query noise only, keep base data for next iteration
    # Actually the outsider has no data, so just clean up the household
    from boto3.dynamodb.conditions import Key
    resp_q = ddb_table.query(KeyConditionExpression=Key("PK").eq(f"HH#{hh_id}"))
    for item in resp_q.get("Items", []):
        ddb_table.delete_item(Key={"PK": item["PK"], "SK": item["SK"]})
