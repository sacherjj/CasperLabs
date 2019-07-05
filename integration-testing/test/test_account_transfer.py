from test.cl_node.docker_node import DockerNode
from test.cl_node.casperlabs_accounts import ACCOUNTS
from test.cl_node.wait import wait_for_blocks_count_at_least


PAYMENT_CONTRACT = 'old_wasm/payment.wasm'


def test_transfer(one_node_network):
    def account_state(block_hash, account):
        return node.d_client.query_state(block_hash=block_hash, key_type='address', key=account, path='')

    node: DockerNode = one_node_network.docker_nodes[0]
    # Transfer 100 from 3030... to 0101...
    block_hash = node.deploy_and_propose(session_contract=ACCOUNTS[1].transfer_contract,
                                         payment_contract=PAYMENT_CONTRACT)
    wait_for_blocks_count_at_least(node, 2, 2)
    response = account_state(block_hash, "3030303030303030303030303030303030303030303030303030303030303030")

    deploys = node.client.show_deploys(block_hash)
    assert deploys[0]['is_error'] != 'true'

    # No API currently exists for getting balance to check transfer.
    # Transfer 175 from 0101... to 0202...
    block_hash = node.deploy_and_propose(session_contract=ACCOUNTS[2].transfer_contract,
                                         payment_contract=PAYMENT_CONTRACT,
                                         from_address=ACCOUNTS[1].public_key_hex)
    wait_for_blocks_count_at_least(node, 3, 3)

    deploys = node.client.show_deploys(block_hash)
    assert not deploys[0].is_error

    # response = account_state(block_hash, ACCOUNTS[1].public_key_hex)

    # Transfer 175 from 0101... to 0202...
    # Should fail
    block_hash = node.deploy_and_propose(session_contract=ACCOUNTS[2].transfer_contract,
                                         payment_contract=PAYMENT_CONTRACT,
                                         from_address=ACCOUNTS[1].public_key_hex)
    wait_for_blocks_count_at_least(node, 4, 4)

    deploys = node.client.show_deploys(block_hash)
    assert not deploys[0].is_error

    response = account_state(block_hash, ACCOUNTS[1].public_key_hex)
    pass
