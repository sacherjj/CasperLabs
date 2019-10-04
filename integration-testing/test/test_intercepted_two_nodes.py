import logging

from casperlabs_local_net.cli import CLI
from casperlabs_local_net.common import Contract
from casperlabs_local_net.wait import wait_for_block_hash_propagated_to_all_nodes
from casperlabs_client import extract_common_name

# fmt: off


def test_gossip_proxy(intercepted_two_node_network):
    nodes = intercepted_two_node_network.docker_nodes
    node = nodes[0]
    account = node.genesis_account

    tls_certificate_path = node.config.tls_certificate_local_path()
    tls_parameters = {
        "--certificate-file": tls_certificate_path,
        "--node-id": extract_common_name(tls_certificate_path),
    }

    cli = CLI(nodes[0], tls_parameters=tls_parameters)
    cli.set_default_deploy_args(
        "--from", account.public_key_hex,
        "--private-key", cli.private_key_path(account),
        "--public-key", cli.public_key_path(account),
        "--payment", cli.resource(Contract.STANDARD_PAYMENT),
        "--payment-args", cli.payment_json,
    )
    cli("deploy", "--session", cli.resource("test_helloname.wasm"))
    block_hash = cli("propose")
    wait_for_block_hash_propagated_to_all_nodes(nodes, block_hash)
    deployInfos = cli("show-deploys", block_hash)
    logging.info(f"=======================================================")
    for deployInfo in deployInfos:
        logging.info(f"{deployInfo}")
