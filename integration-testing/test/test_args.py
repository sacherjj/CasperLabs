#!/usr/bin/env python3

import json
from casperlabs_client import ABI


def test_args_from_json():
    account = bytes(range(32))
    long_value = 123456
    big_int_value = 123456789012345678901234567890
    args = [
        {"name": "amount", "value": {"long_value": long_value}},
        {"name": "account", "value": {"bytes_value": account.hex()}},
        {"name": "purse_id", "value": {"optional_value": {}}},
        {
            "name": "number",
            "value": {"big_int": {"value": f"{big_int_value}", "bit_width": 512}},
        },
    ]

    json_str = json.dumps(args)
    args = ABI.args_from_json(json_str)
    assert args[0] == ABI.long_value("amount", long_value)
    assert args[1] == ABI.account("account", account)
    assert args[2] == ABI.optional_value("purse_id", None)
    assert args[3] == ABI.big_int("number", big_int_value)
