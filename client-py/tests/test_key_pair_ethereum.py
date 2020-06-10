from casperlabs_client.key_pair import EthereumKey


def test_generate_ethereum():
    key = EthereumKey.generate()
    print(key)
