#!/usr/bin/env python3

import json
from casperlabs_client.abi import ABI


account = bytes(range(32))
long_value = 123456
big_int_value = 123456789012345678901234567890


ARGS = [
    {"name": "amount", "value": {"long_value": str(long_value)}},
    {"name": "account", "value": {"bytes_value": account.hex()}},
    {"name": "purse_id", "value": {"optional_value": {}}},
    {
        "name": "number",
        "value": {"big_int": {"value": f"{big_int_value}", "bit_width": 512}},
    },
    {"name": "my_bytes", "value": {"bytes_value": account.hex()}},
    {"name": "my_hash", "value": {"key": {"hash": {"hash": account.hex()}}}},
    {"name": "my_address", "value": {"key": {"address": {"account": account.hex()}}}},
    {
        "name": "my_uref",
        "value": {
            "key": {"uref": {"uref": account.hex(), "access_rights": "READ_ADD"}}
        },
    },
    {"name": "my_local", "value": {"key": {"local": {"hash": account.hex()}}}},
]


def test_args_from_json():
    json_str = json.dumps(ARGS)
    args = ABI.args_from_json(json_str)
    assert args[0] == ABI.long_value("amount", long_value)
    assert args[1] == ABI.account("account", account)
    assert args[2] == ABI.optional_value("purse_id", None)
    assert args[3] == ABI.big_int("number", big_int_value)
    assert args[4] == ABI.bytes_value("my_bytes", account)

    assert args[5] == ABI.key_hash("my_hash", account)
    assert args[6] == ABI.key_address("my_address", account)
    assert args[7] == ABI.key_uref("my_uref", account, access_rights=5)
    assert args[8] == ABI.key_local("my_local", account)


def test_args_to_json():
    args = [
        ABI.long_value("amount", long_value),
        ABI.account("account", account),
        ABI.optional_value("purse_id", None),
        ABI.big_int("number", big_int_value),
        ABI.bytes_value("my_bytes", account),
        ABI.key_hash("my_hash", account),
        ABI.key_address("my_address", account),
        ABI.key_uref("my_uref", account, access_rights=5),
        ABI.key_local("my_local", account),
    ]
    json_str1 = json.dumps(ARGS, ensure_ascii=True, sort_keys=True, indent=2)
    json_str2 = ABI.args_to_json(
        ABI.args(args), ensure_ascii=True, sort_keys=True, indent=2
    )
    assert json_str1 == json_str2
