import pytest

from test.cl_node.docker_node import DockerNode
from test.cl_node.casperlabs_accounts import ACCOUNTS
from test.cl_node.wait import wait_for_blocks_count_at_least


def test_transfer(one_node_network):
    def account_state(block_hash, account):
        return node.d_client.query_state(block_hash=block_hash, key_type='address', key=account, path='')

    genesis_account = ACCOUNTS['genesis'].public_key_hex

    node: DockerNode = one_node_network.docker_nodes[0]
    # Transfer 100 from genesis... to acct1...
    block_hash = node.deploy_and_propose(session_contract=ACCOUNTS[1].transfer_contract,
                                         payment_contract=ACCOUNTS[1].transfer_contract,
                                         from_address=genesis_account)
    wait_for_blocks_count_at_least(node, 2, 2)

    deploys = node.client.show_deploys(block_hash)
    assert not deploys[0].is_error, f"error_message: {deploys[0].error_message}"

    # Response not used, but assures account exist
    response_acct1 = account_state(block_hash, ACCOUNTS[1].public_key_hex)

    # Should error as account doesn't exist.
    with pytest.raises(Exception):
        response_acct2 = account_state(block_hash, ACCOUNTS[2].public_key_hex)

    # No API currently exists for getting balance to check transfer.
    # Transfer 75 from acct1... to acct2...
    block_hash = node.deploy_and_propose(session_contract=ACCOUNTS[2].transfer_contract,
                                         payment_contract=ACCOUNTS[2].transfer_contract,
                                         from_address=ACCOUNTS[1].public_key_hex)
    wait_for_blocks_count_at_least(node, 3, 3)

    deploys = node.client.show_deploys(block_hash)
    assert not deploys[0].is_error, f"error_message: {deploys[0].error_message}"

    # Response not used, but assures account exist
    response_acct2 = account_state(block_hash, ACCOUNTS[2].public_key_hex)

    # Transfer 75 from acct1... to acct2...
    # Should fail with acct1 overdrawn.   Requires assert in contract to generate is_error.
    block_hash = node.deploy_and_propose(session_contract=ACCOUNTS[2].transfer_contract,
                                         payment_contract=ACCOUNTS[2].transfer_contract,
                                         from_address=ACCOUNTS[1].public_key_hex)
    wait_for_blocks_count_at_least(node, 4, 4)

    deploys = node.client.show_deploys(block_hash)
    assert deploys[0].d['is_error']
