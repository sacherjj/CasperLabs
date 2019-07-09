import pytest

from test.cl_node.docker_node import DockerNode
from test.cl_node.casperlabs_accounts import Account


def test_transfer(one_node_network):
    def account_state(block_hash, account):
        return node.d_client.query_state(block_hash=block_hash, key_type='address', key=account, path='')

    genesis_account = Account('genesis').public_key_hex
    acct1 = Account(1)
    acct2 = Account(2)

    node: DockerNode = one_node_network.docker_nodes[0]
    # Transfer 1000000 from genesis... to acct1...
    block_hash = node.deploy_and_propose(session_contract=acct1.transfer_contract,
                                         payment_contract=acct1.transfer_contract,
                                         from_address=genesis_account)

    deploys = node.client.show_deploys(block_hash)
    assert not deploys[0].is_error, f"error_message: {deploys[0].error_message}"

    # Response not used, but assures account exist
    _ = account_state(block_hash, acct1.public_key_hex)

    # Should error as account doesn't exist.
    with pytest.raises(Exception):
        _ = account_state(block_hash, acct2.public_key_hex)

    # No API currently exists for getting balance to check transfer.
    # Transfer 750000 from acct1... to acct2...
    block_hash = node.deploy_and_propose(session_contract=acct2.transfer_contract,
                                         payment_contract=acct2.transfer_contract,
                                         from_address=acct1.public_key_hex)

    deploys = node.client.show_deploys(block_hash)
    assert not deploys[0].is_error, f"error_message: {deploys[0].error_message}"

    # Response not used, but assures account exist
    _ = account_state(block_hash, acct2.public_key_hex)

    # Transfer 750000 from acct1... to acct2...
    # Should fail with acct1 overdrawn.   Requires assert in contract to generate is_error.
    block_hash = node.deploy_and_propose(session_contract=acct2.transfer_contract,
                                         payment_contract=acct2.transfer_contract,
                                         from_address=acct1.public_key_hex)

    deploys = node.client.show_deploys(block_hash)
    assert deploys[0].d['is_error']
