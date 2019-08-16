from test import contract_hash


def test_contract_hash():
    # Check that the old hash values can be calculated correctly.
    # This is what we see in lib.rs when we call a contract:
    # fmt: off
    hash_in_rust = [164, 102, 153, 51, 236, 214, 169, 167, 126, 44, 250, 247, 179,
                    214, 203, 229, 239, 69, 145, 25, 5, 153, 113, 55, 255, 188,
                    176, 201, 7, 4, 42, 100]
    # This is what the error prints if it can't find a key:
    hash_in_hex = 'a4669933ecd6a9a77e2cfaf7b3d6cbe5ef45911905997137ffbcb0c907042a64'
    # This is how it's supposed to be calculated:
    hash_in_bytes = contract_hash('30' * 32, 0, 0)
    assert bytes(hash_in_rust).hex() == hash_in_hex
    assert bytes(hash_in_bytes).hex() == hash_in_hex
    assert list(hash_in_bytes) == hash_in_rust
