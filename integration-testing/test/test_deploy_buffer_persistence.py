from casperlabs_client import ABI
from test.cl_node.casperlabs_accounts import Account
from test.cl_node.common import (
    PAYMENT_CONTRACT,
    MAX_PAYMENT_ABI,
    ADD_ASSOCIATED_KEY_CONTRACT,
)
from test.cl_node.wait import wait_for_good_bye, wait_for_node_started


def test_deploy_buffer_persistence(trillion_payment_node_network):
    node = trillion_payment_node_network.docker_nodes[0]

    # Storing deploys to verify they are processed
    deploy_hashes = []

    def create_and_deploy(acct_num: int):
        node.transfer_to_account(acct_num, 10 ** 8)
        acct = Account(acct_num)
        associated_acct = Account(acct_num + 2)
        args = ABI.args([ABI.account(associated_acct.public_key_binary), ABI.u32(1)])
        _, deploy_hash_bytes = node.p_client.deploy(
            from_address=acct.public_key_hex,
            session_contract=ADD_ASSOCIATED_KEY_CONTRACT,
            payment_contract=PAYMENT_CONTRACT,
            public_key=acct.public_key_path,
            private_key=acct.private_key_path,
            session_args=args,
            payment_args=MAX_PAYMENT_ABI,
        )
        return deploy_hash_bytes.hex()

    # Create Account 1 and associate Account 3
    deploy_hashes.append(create_and_deploy(1))
    # Create Account 2 and associate Account 4
    deploy_hashes.append(create_and_deploy(2))

    block_count_prior = len(list(node.p_client.show_blocks(10)))

    # Restart node to verify persistence
    node.stop()
    wait_for_good_bye(node, 15)
    node.start()
    wait_for_node_started(node, 60, 2)

    # Process through the deploy buffer
    # Currently requires two proposes, but might be optimized to one in future.
    while len(deploy_hashes) > 0:
        response = node.p_client.propose()
        block_hash = response.block_hash.hex()
        for deploy in node.p_client.show_deploys(block_hash):
            deploy_hash = deploy.deploy.deploy_hash.hex()
            assert deploy_hash in deploy_hashes
            deploy_hashes.remove(deploy_hash)

    assert len(deploy_hashes) == 0
    block_count_post = len(list(node.p_client.show_blocks(10)))
    assert block_count_post > block_count_prior
