import pytest
from contextlib import contextmanager
import tempfile

from casperlabs_local_net.common import Contract
from casperlabs_local_net.cli import CLI
from casperlabs_local_net.casperlabs_accounts import Account
from casperlabs_local_net.wait import wait_for_block_hash_propagated_to_all_nodes


@contextmanager
def signed_deploy_file_path(cli, account):
    """
    Creates temp directory, and generates and signs deploy to yield.

    Once context manager call to this method returns, files and directory are cleaned up.

    :param cli: Python CLI object created with proper Node.
    :param account: Account object to use for deploy creation
    :return: Path of signed deploy for use with send-deploy method
    """
    with tempfile.TemporaryDirectory() as tmp_dir:
        unsigned_deploy_path = f"{tmp_dir}/unsigned.deploy"
        signed_deploy_path = f"{tmp_dir}/signed.deploy"

        # fmt: off
        cli(
            "make-deploy",
            "-o", unsigned_deploy_path,
            "--from", account.public_key_hex,
            "--session", cli.resource(Contract.HELLO_NAME_DEFINE),
            "--payment", cli.resource(Contract.STANDARD_PAYMENT),
            "--payment-args", cli.payment_json,
        )

        cli(
            "sign-deploy",
            "-i", unsigned_deploy_path,
            "-o", signed_deploy_path,
            "--private-key", cli.private_key_path(account),
            "--public-key", cli.public_key_path(account),
        )
        # fmt: on

        yield signed_deploy_path


def test_one_network_repeated_deploy(one_node_network_fn):
    """
    Test that a repeated deploy is rejected on same node.
    """
    node = one_node_network_fn.docker_nodes[0]
    cli = CLI(node)
    account = Account("genesis")

    # Generate and save signed_deploy
    with signed_deploy_file_path(cli, account) as signed_deploy_path:
        # First deployment of signed_deploy, should succeed
        deploy_hash = cli("send-deploy", "-i", signed_deploy_path)
        cli("propose")
        deploy_info = cli("show-deploy", deploy_hash)
        assert not deploy_info.processing_results[0].is_error

        # Second deployment of signed_deploy should fail
        deploy_hash = cli("send-deploy", "-i", signed_deploy_path)
        deploy_info = cli("show-deploy", deploy_hash)
        assert not deploy_info.processing_results[0].is_error
        with pytest.raises(Exception) as excinfo:
            cli("propose")
        assert "No new deploys" in str(excinfo.value)


def test_two_network_repeated_deploy(two_node_network):
    """
    Test that a repeated deploy is rejected on a different node
    """
    nodes = two_node_network.docker_nodes
    clis = [CLI(node) for node in nodes]
    accounts = (Account("genesis"), Account(1))

    # Generate and save signed_deploy
    with signed_deploy_file_path(clis[0], accounts[0]) as signed_deploy_path:
        # First deployment of signed_deploy from node-0 should succeed
        deploy_hash = clis[0]("send-deploy", "-i", signed_deploy_path)
        block_hash = clis[0]("propose")
        deploy_info = clis[0]("show-deploy", deploy_hash)
        assert not deploy_info.processing_results[0].is_error

        wait_for_block_hash_propagated_to_all_nodes(nodes, block_hash)

        # Second deployment of signed_deploy to node-1 should fail.
        deploy_hash = clis[1]("send-deploy", "-i", signed_deploy_path)
        deploy_info = clis[1]("show-deploy", deploy_hash)
        assert not deploy_info.processing_results[0].is_error
        with pytest.raises(Exception) as excinfo:
            clis[1]("propose")
        assert "No new deploys" in str(excinfo.value)
