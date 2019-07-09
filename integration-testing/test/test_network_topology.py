import logging

from .cl_node.wait import (
    wait_for_block_contains,
    wait_for_new_fork_choice_tip_block,
)


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
        node.deploy_and_propose(session_contract="test_helloname.wasm",
                                payment_contract="test_helloname.wasm",
                                private_key="validator-0-private.pem",
                                public_key="validator-0-public.pem")


def test_star_network(star_network):
    # deploy and propose from one of the star edge nodes.
    node1 = star_network.docker_nodes[1]
    block = node1.deploy_and_propose(session_contract="test_helloname.wasm",
                                     payment_contract="test_helloname.wasm",
                                     private_key="validator-0-private.pem",
                                     public_key="validator-0-public.pem")

    # validate all nodes get block
    for node in star_network.docker_nodes:
        wait_for_new_fork_choice_tip_block(node, block, node.timeout)
