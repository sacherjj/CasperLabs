import os
import logging
from casperlabs_local_net.cli import DockerCLI, CLI
from casperlabs_client import CasperLabsClient, extract_common_name


def test_grpc_encryption_scala_cli(encrypted_two_node_network):
    node = encrypted_two_node_network.docker_nodes[0]
    cli = DockerCLI(
        node,
        tls_parameters={
            "--node-id": extract_common_name(node.config.tls_certificate_local_path())
        },
    )
    logging.info(
        f"EXECUTING {' '.join(cli.expand_args(['show-blocks', '--depth', 1]))}"
    )
    blocks = cli("show-blocks", "--depth", 1)
    logging.debug(f"{blocks}")


def test_grpc_encryption_python_lib(encrypted_two_node_network):
    node = encrypted_two_node_network.docker_nodes[0]
    host = os.environ.get("TAG_NAME", None) and node.container_name or "localhost"
    client = CasperLabsClient(
        host,
        node.grpc_external_docker_port,
        node.grpc_internal_docker_port,
        extract_common_name(node.config.tls_certificate_local_path()),
        node.config.tls_certificate_local_path(),
    )
    blocks = list(client.showBlocks(1))
    assert len(blocks) > 0
    logging.debug(f"{blocks}")


def test_grpc_encryption_python_cli(encrypted_two_node_network):
    nodes = encrypted_two_node_network.docker_nodes

    # The idea of the test was to check if it is possible to provide the client
    # with a certificate other than the node's certificate, and overwrite it with
    # node_id (CN in node's certificate). Unfortunately, it doesn't work, i.e.
    # we can do it but connection will fail with:
    # "Handshake failed with fatal error SSL_ERROR_SSL: error:1000007d:SSL routines:OPENSSL_internal:CERTIFICATE_VERIFY_FAILED."
    # If not for the above the "--certificate-file" option would point to certficate of the nodes[1]
    # in this test.
    tls_parameters = {
        "--certificate-file": nodes[0].config.tls_certificate_local_path(),
        "--node-id": extract_common_name(nodes[0].config.tls_certificate_local_path()),
    }
    cli = CLI(nodes[0], tls_parameters=tls_parameters)
    logging.info(
        f"""EXECUTING {' '.join(cli.expand_args(["show-blocks", "--depth", 1]))}"""
    )
    blocks = cli("show-blocks", "--depth", 1)
    assert len(blocks) > 0
    logging.debug(f"{blocks}")
