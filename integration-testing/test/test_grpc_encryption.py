from test.cl_node.cli import DockerCLI, CLI
import logging
from casperlabs_client import CasperLabsClient


def test_grpc_encryption_scala(encrypted_one_node_network):
    node = encrypted_one_node_network.docker_nodes[0]
    cli = DockerCLI(node, grpc_encryption=True)
    blocks = cli("show-blocks", "--depth", 1)
    logging.info(f"{blocks}")


def test_grpc_encryption_python(encrypted_one_node_network):
    node = encrypted_one_node_network.docker_nodes[0]
    cli = CLI(node, grpc_encryption=True)

    client = CasperLabsClient(cli.host, cli.port, cli.internal_port, cli.cert_file)
    blocks = client.showBlocks(10)
    logging.info(f"{list(blocks)}")
