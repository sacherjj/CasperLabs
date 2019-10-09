from casperlabs_local_net.cli import DockerCLI
from casperlabs_local_net.common import Contract
from casperlabs_local_net.wait import wait_for_block_hash_propagated_to_all_nodes
from casperlabs_client import extract_common_name
from pytest import raises

# fmt: off


def test_check_deploy_signatures(intercepted_two_node_network):
    """
    This tests uses an interceptor that modifies block retrieved
    by node-1 from node-0 with GetBlockChunked method of the gossip service
    and removes approvals from deploys in the block.
    node-1 should not accept this block.
    """
    nodes = intercepted_two_node_network.docker_nodes
    node = nodes[0]
    account = node.genesis_account

    tls_certificate_path = node.config.tls_certificate_local_path()
    tls_parameters = {
        # Currently only Python client requires --certificate-file
        # It may not need it in the future.
        # "--certificate-file": tls_certificate_path,
        "--node-id": extract_common_name(tls_certificate_path),
    }

    cli = DockerCLI(nodes[0], tls_parameters=tls_parameters)
    cli.set_default_deploy_args(
        "--from", account.public_key_hex,
        "--private-key", cli.private_key_path(account),
        "--public-key", cli.public_key_path(account),
        "--payment", cli.resource(Contract.STANDARD_PAYMENT),
        "--payment-args", cli.payment_json,
    )
    cli("deploy", "--session", cli.resource(Contract.HELLO_NAME_DEFINE))
    block_hash = cli("propose")

    with raises(Exception):
        wait_for_block_hash_propagated_to_all_nodes(nodes, block_hash)

    assert 'InvalidDeploySignature' in nodes[1].logs()
