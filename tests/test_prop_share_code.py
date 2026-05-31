"""Property 8: Share Code Single-Use.

**Validates: Requirements 7.2, 7.3**

A share code is usable exactly once. Once redeemed, subsequent redemption
attempts are rejected.
"""

from __future__ import annotations

import json
import time
from datetime import datetime, timezone, timedelta

from hypothesis import given, settings, HealthCheck
from hypothesis import strategies as st

from conftest import make_event, seed_household
from share_code.handler import handler


@settings(max_examples=20, suppress_health_check=[HealthCheck.function_scoped_fixture])
@given(num_redeem_attempts=st.integers(min_value=2, max_value=5))
def test_share_code_single_use(ddb_table, num_redeem_attempts: int) -> None:
    """A share code can only be redeemed once; subsequent attempts fail."""
    hh_id = "hh-share"
    owner_sub = "user-owner"

    seed_household(ddb_table, hh_id, owner_sub)

    # Generate a share code
    gen_event = make_event(
        method="POST",
        path="/household/share-code",
        user_sub=owner_sub,
    )
    gen_resp = handler(gen_event, None)
    assert gen_resp["statusCode"] == 201
    code = json.loads(gen_resp["body"])["code"]

    # First redemption by a new user succeeds
    joiner_sub = "user-joiner-0"
    join_event = make_event(
        method="POST",
        path="/household/join",
        body={"code": code},
        user_sub=joiner_sub,
        claims_extra={"name": "Joiner"},
    )
    join_resp = handler(join_event, None)
    assert join_resp["statusCode"] == 200

    # Subsequent redemption attempts by different users are rejected
    for i in range(1, num_redeem_attempts):
        next_joiner = f"user-joiner-{i}"
        next_event = make_event(
            method="POST",
            path="/household/join",
            body={"code": code},
            user_sub=next_joiner,
            claims_extra={"name": f"Joiner{i}"},
        )
        next_resp = handler(next_event, None)
        assert next_resp["statusCode"] == 409
        body = json.loads(next_resp["body"])
        assert body["error"] == "SHARE_CODE_REDEEMED"

    # Cleanup
    from boto3.dynamodb.conditions import Key
    resp = ddb_table.query(KeyConditionExpression=Key("PK").eq(f"HH#{hh_id}"))
    for item in resp.get("Items", []):
        ddb_table.delete_item(Key={"PK": item["PK"], "SK": item["SK"]})
