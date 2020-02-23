import logging
from casperlabs_client.abi import ABI
from casperlabs_local_net.casperlabs_accounts import Account
from casperlabs_local_net.common import Contract
from casperlabs_local_net.wait import wait_for_good_bye, wait_for_node_started


def test_deploy_buffer_persistence(trillion_payment_node_network):
    """
    Creates accounts, then creates deploys for these accounts while storing deploy_hashes.
    Shuts down the node.  Starts back up the node.
    Persistence should still have deploys ready for propose.
    Proposes until all deploys are added to blocks.
    """

    node = trillion_payment_node_network.docker_nodes[0]

    def create_associate_deploy(acct_num: int):
        """ Add associated key of acct_num + 1 to acct_num account """
        acct = Account(acct_num)
        associated_acct = Account(acct_num + 1)
        args = ABI.args(
            [
                ABI.account("account", associated_acct.public_key_binary),
                ABI.u32("amount", 1),
            ]
        )
        return node.p_client.deploy(
            from_address=acct.public_key_hex,
            session_contract=Contract.ADD_ASSOCIATED_KEY,
            public_key=acct.public_key_path,
            private_key=acct.private_key_path,
            session_args=args,
        )

    # Currently hit gRPC limit with greater than around 16.
    acct_count = 8

    # We will add associated key for acct_num + 1, so skipping by 2.
    odd_acct_numbers = list(range(1, acct_count * 2 + 1, 2))

    # Create accounts for use
    for acct_num in odd_acct_numbers:
        node.transfer_to_account(acct_num, 10 ** 8)

    # Adding associated key for even account numbers one greater than acct_num
    # Storing deploys to verify they are processed
    deploy_hashes = [create_associate_deploy(acct_num) for acct_num in odd_acct_numbers]

    block_count_prior = len(list(node.p_client.show_blocks(1000)))

    # Restart node to verify persistence
    node.stop()
    wait_for_good_bye(node, 15)
    node.start()
    wait_for_node_started(node, 60, 2)

    block_hashes = set(
        node.wait_for_deploy_processed_and_get_block_hash(deploy_hash)
        for deploy_hash in deploy_hashes
    )
    logging.info(
        f"=== Deploy hashes {deploy_hashes} processed and included in {len(block_hashes)} blocks: {block_hashes}"
    )

    block_count_post = len(list(node.p_client.show_blocks(1000)))
    logging.info(f"Blocks before restarting: {block_count_prior}")
    logging.info(f"Blocks after restarting: {block_count_post}")

    # Check there were some new blocks created after restart.
    assert block_count_post > block_count_prior
