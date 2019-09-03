import os
import logging
from test.cl_node.cli import DockerCLI, CLI
from casperlabs_client import CasperLabsClient, extract_common_name


def test_grpc_encryption_scala_cli(encrypted_two_node_network):
    node = encrypted_two_node_network.docker_nodes[0]
    cli = DockerCLI(
        node,
        tls_parameters={
            "--node-id": extract_common_name(node.config.tls_certificate_local_path())
        },
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
        None,  # extract_common_name(node.config.tls_certificate_local_path()),
        node.config.tls_certificate_local_path(),
    )
    blocks = list(client.showBlocks(1))
    assert len(blocks)
    logging.debug(f"{blocks}")


def test_grpc_encryption_python_cli(encrypted_two_node_network):
    nodes = encrypted_two_node_network.docker_nodes
    cli = CLI(
        nodes[0],
        tls_parameters={
            "--certificate-file": nodes[0].config.tls_certificate_local_path(),
            # "--node-id": extract_common_name(nodes[0].config.tls_certificate_local_path()),
        },
    )
    blocks = cli("show-blocks", "--depth", 1)
    assert len(blocks)
    logging.debug(f"{blocks}")
