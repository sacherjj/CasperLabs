from casperlabs_client import ABI
from test.cl_node.casperlabs_accounts import Account
from test.cl_node.common import (
    PAYMENT_CONTRACT,
    MAX_PAYMENT_ABI,
    ADD_ASSOCIATED_KEY_CONTRACT,
)
from test.cl_node.wait import wait_for_good_bye, wait_for_node_started


def test_deploy_buffer_persistence(trillion_payment_node_network):
    """
    Creates accounts, then creates deploys for these accounts while storing deploy_hashes.
    Shuts down the node.  Starts back up the node.
    Persistence should still have deploys ready for propose.
    Proposes until all deploys are added to blocks.
    """

    node = trillion_payment_node_network.docker_nodes[0]

    # Storing deploys to verify they are processed
    deploy_hashes = []

    def create_associate_deploy(acct_num: int):
        """ Add associated key of acct_num + 1 to acct_num account """
        acct = Account(acct_num)
        associated_acct = Account(acct_num + 1)
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

    # Currently hit gRPC limit with greater than around 16.
    acct_count = 2

    # We will add associated key for acct_num + 1, so skipping by 2.
    odd_acct_numbers = list(range(1, acct_count * 2 + 1, 2))

    # Create accounts for use
    for acct_num in odd_acct_numbers:
        node.transfer_to_account(acct_num, 10 ** 8)

    # Adding associated key for even account numbers one greater than acct_num
    for acct_num in odd_acct_numbers:
        deploy_hashes.append(create_associate_deploy(acct_num))

    block_count_prior = len(list(node.p_client.show_blocks(1000)))

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
            assert (
                deploy_hash in deploy_hashes
            ), f"Expected deploy hash: {deploy_hash} in previous hashes: {deploy_hashes}."
            deploy_hashes.remove(deploy_hash)

    # Validate all deploy hashes were used and new blocks were proposes.
    assert len(deploy_hashes) == 0, "We did not propose all stored deploy_hashes."
    block_count_post = len(list(node.p_client.show_blocks(1000)))
    assert block_count_post > block_count_prior
