import contextlib
import logging
from typing import Generator

from . import conftest
from .cl_node.casperlabsnode import (
    CONTRACT_NAME,
    docker_network_with_started_bootstrap,
    start_network,
    complete_network,
    deploy_and_propose
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


def test_metrics_api_socket(command_line_options_fixture, docker_client_fixture):
    with conftest.testing_context(command_line_options_fixture, docker_client_fixture) as context:
        with complete_network(context) as network:
            for node in network.nodes:
                exit_code, _ = node.get_metrics()
                assert exit_code == 0, "Could not get the metrics for node {node.name}"


def check_blocks(node, expected_string, network, context, block_hash):
    logging.info("Check all peer logs for blocks containing {}".format(expected_string))

    other_nodes = [n for n in network.nodes if n.container.name != node.container.name]

    for node in other_nodes:
        wait_for_block_contains(node, block_hash, expected_string, context.receive_timeout)


def mk_expected_string(node, random_token):
    return "<{name}:{random_token}>".format(name=node.container.name, random_token=random_token)


def casper_propose_and_deploy(network):
    """Deploy a contract and then checks for the block hash proposed.

    TODO: checking blocks for strings functionality has been truncated from this test case.
    Need to add once the PR https://github.com/CasperLabs/CasperLabs/pull/142 has been merged.
    """
    for node in network.nodes:
        logging.info("Run test on node '{}'".format(node.name))
        deploy_and_propose(node, CONTRACT_NAME)


def test_casper_propose_and_deploy(command_line_options_fixture, docker_client_fixture):
    with conftest.testing_context(command_line_options_fixture, docker_client_fixture) as context:
        with complete_network(context) as network:
            casper_propose_and_deploy(network)


def test_convergence(command_line_options_fixture, docker_client_fixture):
    with conftest.testing_context(command_line_options_fixture, docker_client_fixture) as context:
        with complete_network(context) as network:
            pass
