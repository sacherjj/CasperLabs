from pytest import raises

from test.cl_node.docker_node import DockerNode
from test.cl_node.casperlabs_accounts import Account


def test_transfer_with_overdraft(one_node_network):

    def account_state(block_hash, account):
        return node.d_client.query_state(block_hash=block_hash, key_type='address', key=account, path='')

    acct1 = Account(1)
    acct2 = Account(2)

    node: DockerNode = one_node_network.docker_nodes[0]
    # Transfer 1000000 from genesis... to acct1...
    block_hash = node.transfer_to_account(to_account_id=1, amount=1000000, from_account_id='genesis')

    deploys = node.client.show_deploys(block_hash)
    assert not deploys[0].is_error, f"error_message: {deploys[0].error_message}"

    # Response not used, but assures account exist
    _ = account_state(block_hash, acct1.public_key_hex)

    # Should error as account doesn't exist.
    with raises(Exception):
        _ = account_state(block_hash, acct2.public_key_hex)

    # No API currently exists for getting balance to check transfer.
    # Transfer 750000 from acct1... to acct2...
    block_hash = node.transfer_to_account(to_account_id=2, amount=750000, from_account_id=1)

    deploys = node.client.show_deploys(block_hash)
    assert not deploys[0].is_error, f"error_message: {deploys[0].error_message}"

    # Response not used, but assures account exist
    _ = account_state(block_hash, acct2.public_key_hex)

    # Transfer 750000 from acct1... to acct2...
    # Should fail with acct1 overdrawn.   Requires assert in contract to generate is_error.
    with raises(Exception):
        _ = node.transfer_to_account(to_account_id=2, amount=750000, from_account_id=1)


def test_transfer_to_accounts(one_node_network):
    node: DockerNode = one_node_network.docker_nodes[0]
    # Perform multiple transfers with end result of Acct1 = 100, Acct2 = 100, Acct3 = 800
    node.transfer_to_accounts([(1, 1000), (2, 900, 1), (3, 800, 2)])
    with raises(Exception):
        # Acct 1 only has 100, so should fail
        node.transfer_to_account(to_account_id=4, amount=101, from_account_id=1)
    node.transfer_to_account(to_account_id=4, amount=100, from_account_id=2)
    # TODO: Improve checks once balance is easy to read.
