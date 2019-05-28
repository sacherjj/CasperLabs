
from test import conftest
from test.cl_node.casperlabsnode import (
    HELLO_NAME,
    HELLO_WORLD,
    complete_network,
    deploy,
    propose,
)
from test.cl_node.wait import (
    wait_for_connected_to_node,
    wait_for_finalised_hash,
    wait_for_good_bye,
    wait_for_metrics_and_assert_blocks_avaialable,
    wait_for_node_started,
    wait_for_received_approved_block_request,
    wait_for_requested_for_fork_tip,
    wait_for_sending_approved_block_request,
    wait_for_streamed_packet,
    wait_for_blocks_count_at_least,
)


# TODO: Fix finalized hash portion
def ignore_test_persistent_dag_store(two_node_network):
    """
    Feature file: storage.feature
    """
    node0, node1 = two_node_network.docker_nodes
    for node in two_node_network.docker_nodes:
        node.deploy_and_propose(session_contract=HELLO_NAME)

    two_node_network.stop_cl_node(1)
    two_node_network.start_cl_node(1)

    timeout = node0.config.command_timeout

    wait_for_connected_to_node(node0, node1.name, timeout, 2)

    hash_string = node0.deploy_and_propose(session_contract=HELLO_NAME)

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
    """
    tnn = two_node_network
    node0, node1 = tnn.docker_nodes
    for node in (node0, node1):
        node.deploy_and_propose()

    wait_for_blocks_count_at_least(node0, 3, 4, 10)
    wait_for_blocks_count_at_least(node1, 3, 4, 10)

    dag0 = node0.vdag(10)
    dag1 = node1.vdag(10)
    blocks0 = node0.show_blocks_with_depth(10)
    blocks1 = node1.show_blocks_with_depth(10)

    for node_num in range(2):
        tnn.stop_cl_node(node_num)
    for node_num in range(2):
        tnn.start_cl_node(node_num)

    wait_for_blocks_count_at_least(node0, 3, 4, 20)
    wait_for_blocks_count_at_least(node1, 3, 4, 20)

    assert dag0 == node0.vdag(10)
    assert dag1 == node1.vdag(10)
    assert blocks0 == node0.show_blocks_with_depth(10)
    assert blocks1 == node1.show_blocks_with_depth(10)
