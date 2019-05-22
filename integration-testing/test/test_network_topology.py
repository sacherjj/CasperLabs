import contextlib
import logging
from typing import Generator

from .cl_node.casperlabsnode import (
    HELLO_NAME,
    deploy,
    docker_network_with_started_bootstrap,
    propose,
    start_network,
)
from .cl_node.common import Network, TestingContext
from .cl_node.wait import (
    wait_for_block_contains,
    wait_for_converged_network,
    wait_for_started_network,
)


@contextlib.contextmanager
def star_network(context: TestingContext) -> Generator[Network, None, None]:
    with docker_network_with_started_bootstrap(context) as bootstrap_node:
        with start_network(context=context, bootstrap=bootstrap_node) as network:
            wait_for_started_network(context.node_startup_timeout, network)
            wait_for_converged_network(context.network_converge_timeout, network, 1)
            yield network


def test_metrics_api_socket(two_node_network):
    for node in two_node_network.docker_nodes:
        exit_code, _ = node.get_metrics()
        assert exit_code == 0, "Could not get the metrics for node {node.name}"


def check_blocks(node, expected_string, network, context, block_hash):
    logging.info("Check all peer logs for blocks containing {}".format(expected_string))

    other_nodes = [n for n in network.nodes if n.container.name != node.container.name]

    for node in other_nodes:
        wait_for_block_contains(node, block_hash, expected_string, context.receive_timeout)


def mk_expected_string(node, random_token):
    return "<{name}:{random_token}>".format(name=node.container.name, random_token=random_token)


def test_casper_propose_and_deploy(two_node_network):
    for node in two_node_network.docker_nodes:
        node.deploy_and_propose()


def test_convergence(three_node_network):
    pass
