from casperlabs_local_net.common import Contract
from casperlabs_local_net.wait import (
    wait_for_block_hash_propagated_to_all_nodes,
    wait_for_node_started,
)
import logging


def test_multiple_bootstraps(three_node_network_with_two_bootstraps):

    # Successful setup of fixture three_node_network_with_two_bootstraps
    # means that node-2, configured for multiple bootstrap nodes,
    # could start when node-0 and node-1 were up.

    net = three_node_network_with_two_bootstraps
    nodes = net.docker_nodes

    block_hash = nodes[0].deploy_and_get_block_hash(
        nodes[0].genesis_account, Contract.HELLO_NAME_DEFINE
    )
    wait_for_block_hash_propagated_to_all_nodes(nodes, block_hash)

    # Stop node-2 and node-0, leave node-1 running.
    # Clear state of node-2 before stopping it so when we restart it
    # it has to download the genesis block again.
    nodes[2].clear_state()
    net.stop_cl_node(2)
    net.stop_cl_node(0)

    # Start node-2 and check it can bootstrap from node-1.
    net.start_cl_node(2)

    block_hash = nodes[1].deploy_and_get_block_hash(
        nodes[1].genesis_account, Contract.HELLO_NAME_DEFINE
    )
    wait_for_block_hash_propagated_to_all_nodes([nodes[1], nodes[2]], block_hash)

    net.start_cl_node(0)


def test_standalone_nodes_bootstrap_from_each_other(
    three_node_network_with_two_bootstraps
):
    net = three_node_network_with_two_bootstraps
    nodes = net.docker_nodes

    block_hash = nodes[0].deploy_and_get_block_hash(
        nodes[0].genesis_account, Contract.HELLO_NAME_DEFINE
    )
    wait_for_block_hash_propagated_to_all_nodes(nodes, block_hash)

    logging.info(f"======= Clearing state of {nodes[0].address}")

    nodes[0].clear_state()

    logging.info(f"======= Stopping {nodes[0].address}")
    net.stop_cl_node(0)

    logging.info(f"======= Starting {nodes[0].address}")
    net.start_cl_node(0)

    wait_for_node_started(nodes[0], 60, 1)
