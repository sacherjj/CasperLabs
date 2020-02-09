import os
import logging
from casperlabs_local_net.cli import DockerCLI, CLI, CLIErrorExit
from casperlabs_client import CasperLabsClient, extract_common_name
from pytest import raises
from casperlabs_local_net import grpc_proxy


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


def local_path(p):
    return "resources/bootstrap_certificate/" + p.split("/")[-1]


def test_grpc_encryption_python_cli_and_proxy(encrypted_two_node_network):
    node = encrypted_two_node_network.docker_nodes[0]

    tls_certificate_path = node.config.tls_certificate_local_path()
    tls_key_path = local_path(node.config.tls_key_path())
    tls_parameters = {
        "--certificate-file": tls_certificate_path,
        "--node-id": extract_common_name(tls_certificate_path),
    }

    cli = CLI(node, tls_parameters=tls_parameters)
    cli.port = 50401  # We will be talking to proxy on 50401, node is on 40401

    proxy_client = grpc_proxy.proxy_client(
        node,
        node_port=40401,
        node_host=cli.host,
        proxy_port=50401,
        client_certificate_file=tls_certificate_path,
        client_key_file=tls_key_path,
        server_certificate_file=tls_certificate_path,
        server_key_file=tls_key_path,
    )

    cli.host = "localhost"

    logging.info(
        f"""EXECUTING {' '.join(cli.expand_args(["show-blocks", "--depth", 1]))}"""
    )
    block_infos = cli("show-blocks", "--depth", 1)
    logging.info(f"{block_infos[0]}")
    assert len(block_infos) > 0
    block_info = cli("show-block", block_infos[0].summary.block_hash)
    logging.info(f"{block_info}")

    proxy_client.stop()

    with raises(CLIErrorExit) as excinfo:
        block_info = cli("show-block", block_infos[0].summary.block_hash)
    # Expected grpc error: UNAVAILABLE
    assert '"grpc_status":14' in str(excinfo.value)
