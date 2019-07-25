from test.cl_node.docker_node import DockerNode
from test.cl_node.casperlabs_accounts import Account


def balance(node, account_address, block_hash):
    try:
        return node.client.get_balance(account_address, block_hash)
    except:
        return 0


def test_scala_client_balance(one_node_network):
    node: DockerNode = one_node_network.docker_nodes[0]

    accounts = [Account(i) for i in range(1, 4)]

    block_hash = list(node.p_client.show_blocks(1))[0].summary.block_hash.hex()

    initial = [balance(node, account.public_key_hex, block_hash) for account in accounts]

    # Perform multiple transfers with end result of Acct1 = 200, Acct2 = 100, Acct3 = 700
    hashes = node.transfer_to_accounts([(1, 1000), (2, 800, 1), (3, 700, 2)])

    assert node.d_client.get_balance(account_address=accounts[0].public_key_hex, block_hash=hashes[-1]) == initial[0] + 200
    assert node.d_client.get_balance(account_address=accounts[1].public_key_hex, block_hash=hashes[-1]) == initial[1] + 100
    assert node.d_client.get_balance(account_address=accounts[2].public_key_hex, block_hash=hashes[-1]) == initial[2] + 700
