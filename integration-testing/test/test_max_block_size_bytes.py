from casperlabs_local_net.casperlabs_accounts import GENESIS_ACCOUNT, Account
from casperlabs_client.abi import ABI
from casperlabs_client.utils import bundled_contract
import logging


def make_transfers(client, account, target_account, amount, n):
    """
    Makes n transfers from account to account_target,
    n must be greater than 1.

    First n-1 deploys depends on a deploy that is sent to the node as the last one.
    This is in order to ensure that node doesn't put part of the set of deploys on a block
    before receiving all of them.

    Returns tuple (deploy_hash, [deploy_hashes]), where deploy_hash is the special
    deploy that all other deploys depend on.
    """
    if not n > 1:
        raise Exception("n must be > 1")

    deploy = client.make_deploy(
        from_addr=account.public_key_hex,
        session=bundled_contract("transfer_to_account_u512.wasm"),
        session_args=ABI.args(
            [
                ABI.account("account", bytes.fromhex(target_account.public_key_hex)),
                ABI.u512("amount", 1),
            ]
        ),
        payment_amount=10000000,
    )
    deploy = client.sign_deploy(
        deploy, account.public_key_hex, account.private_key_path
    )
    deploy_hash = deploy.deploy_hash.hex()

    deploy_hashes = [
        client.transfer(
            private_key=account.private_key_path,
            from_addr=account.public_key_hex,
            target_account_hex=target_account.public_key_hex,
            payment_amount=10000000,
            amount=1,
            dependencies=[deploy_hash],
        )
        for _ in range(n - 1)
    ]

    client.send_deploy(deploy)
    return deploy_hash, deploy_hashes


# resources/test-chainspec-minor/genesis/manifest.toml:  max-block-size-bytes = 10485760
def test_max_block_size_bytes(chainspec_upgrades_network_minor):
    net = chainspec_upgrades_network_minor
    node = net.docker_nodes[0]
    client = node.p_client.client
    account = GENESIS_ACCOUNT
    target_account = Account(1)
    number_of_deploys = 15

    deploy_hash, deploy_hashes = make_transfers(
        client, account, target_account, 1, number_of_deploys
    )

    deploy_info = client.showDeploy(deploy_hash, wait_for_processed=True)
    logging.info(f"==>  {deploy_info}")

    deploy_infos = list(
        client.showDeploys(
            deploy_info.processing_results[0].block_info.summary.block_hash.hex(),
            full_view=False,
        )
    )
    logging.info(f"==>  {deploy_infos}")

    assert len(deploy_infos) > 1
