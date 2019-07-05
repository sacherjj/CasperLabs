from test.cl_node.casperlabs_accounts import ACCOUNTS


def test_accounts_public_key_hex():
    assert ACCOUNTS[1].public_key == '11111111111111111111111111111111'
    assert ACCOUNTS[1].public_key_hex == '0101010101010101010101010101010101010101010101010101010101010101'
