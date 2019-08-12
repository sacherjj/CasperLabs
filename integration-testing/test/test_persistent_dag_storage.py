from test.cl_node.common import HELLO_NAME
from test.cl_node.wait import (
    wait_for_connected_to_node,
    wait_for_finalised_hash,
    wait_for_metrics_and_assert_blocks_avaialable,
    wait_for_received_approved_block_request,
    wait_for_sending_approved_block_request,
    wait_for_streamed_packet,
    wait_for_block_hashes_propagated_to_all_nodes,
)


# TODO: Fix finalized hash portion
def ignore_test_persistent_dag_storage(two_node_network):
    """
    Feature file: storage.feature
    Scenario: Stop a node in network, and restart it. Assert that it downloads only latest block not the whole DAG.
    """
    node0, node1 = two_node_network.docker_nodes
    for node in two_node_network.docker_nodes:
        node.deploy_and_propose(
            session_contract=HELLO_NAME, payment_contract=HELLO_NAME
        )

    two_node_network.stop_cl_node(1)
    two_node_network.start_cl_node(1)

    timeout = node0.config.command_timeout

    wait_for_connected_to_node(node0, node1.name, timeout, 2)

    hash_string = node0.deploy_and_propose(
        session_contract=HELLO_NAME, payment_contract=HELLO_NAME
    )

    wait_for_sending_approved_block_request(node0, node1.name, timeout)
    wait_for_received_approved_block_request(node0, node1.name, timeout)
    wait_for_streamed_packet(node0, node1.name, timeout)

    wait_for_finalised_hash(node0, hash_string, timeout * 2)
    wait_for_finalised_hash(node1, hash_string, timeout * 2)

    number_of_blocks = 1
    wait_for_metrics_and_assert_blocks_avaialable(node1, timeout, number_of_blocks)


def test_storage_after_multiple_node_deploy_propose_and_shutdown(two_node_network):
    """
    Feature file: storage.feature
    Scenario: Stop nodes and restart with correct dag and blockstorage
    """
    tnn = two_node_network
    node0, node1 = tnn.docker_nodes
    block_hashes = [
        node.deploy_and_propose(
            session_contract=HELLO_NAME, payment_contract=HELLO_NAME
        )
        for node in (node0, node1)
    ]

    wait_for_block_hashes_propagated_to_all_nodes(tnn.docker_nodes, block_hashes)

    dag0 = node0.client.vdag(10)
    dag1 = node1.client.vdag(10)
    blocks0 = node0.client.show_blocks(10)
    blocks1 = node1.client.show_blocks(10)

    for node_num in range(2):
        tnn.stop_cl_node(node_num)
    for node_num in range(2):
        tnn.start_cl_node(node_num)

    wait_for_block_hashes_propagated_to_all_nodes(tnn.docker_nodes, block_hashes)

    assert dag0 == node0.client.vdag(10)
    assert dag1 == node1.client.vdag(10)
    assert blocks0 == node0.client.show_blocks(10)
    assert blocks1 == node1.client.show_blocks(10)
