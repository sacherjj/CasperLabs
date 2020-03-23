from casperlabs_local_net.contract_address import contract_address


def disabled_test_contract_address():
    # TODO: Method of computing function address changed.
    # Test needs to be updated.

    # Check that the old hash values can be calculated correctly.
    # This is what we see in lib.rs when we call a contract:
    # fmt: off
    hash_in_rust = [180, 116, 58, 59, 37, 12, 193, 177, 220, 145, 181, 149, 90, 157, 70, 154,
                    205, 92, 131, 190, 6, 254, 176, 22, 198, 39, 75, 234, 137, 9, 48, 73]
    # fmt: on

    hash_in_hex = "b4743a3b250cc1b1dc91b5955a9d469acd5c83be06feb016c6274bea89093049"

    # This is how it's supposed to be calculated:
    hash_in_bytes = contract_address("30" * 32, 0)
    assert bytes(hash_in_rust).hex() == hash_in_hex
    assert bytes(hash_in_bytes).hex() == hash_in_hex
    assert list(hash_in_bytes) == hash_in_rust
