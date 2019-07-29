from test.cl_node.docker_node import DockerNode
from test.cl_node.casperlabs_accounts import Account


def test_scala_client_balance(one_node_network):
    node: DockerNode = one_node_network.docker_nodes[0]

    # This is only in scala client, need to verify we are using correct one.
    node.use_docker_client()

    acct1, acct2, acct3 = [Account(i) for i in range(1, 4)]

    # Perform multiple transfers with end result of Acct1 = 200, Acct2 = 100, Acct3 = 700
    hashes = node.transfer_to_accounts([(1, 1000), (2, 800, 1), (3, 700, 2)])

    assert node.client.get_balance(account_address=acct1.public_key_hex, block_hash=hashes[-1]) == 200
    assert node.client.get_balance(account_address=acct2.public_key_hex, block_hash=hashes[-1]) == 100
    assert node.client.get_balance(account_address=acct3.public_key_hex, block_hash=hashes[-1]) == 700
