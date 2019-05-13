from . import conftest
from .cl_node.casperlabsnode import (
    HELLO_NAME,
    complete_network,
    deploy,
    propose,
)
from .cl_node.wait import (
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


def test_persistent_dag_store(command_line_options_fixture, docker_client_fixture):
    with conftest.testing_context(
        command_line_options_fixture,
        docker_client_fixture,
        peers_keypairs=[]  # will create a single peer network
    ) as context:
        with complete_network(context) as network:
            for node in network.nodes:
                deploy(node, HELLO_NAME)
                propose(node, HELLO_NAME)
            node0 = network.peers[0]
            engine0 = network.engines[0]
            engine0.stop()
            node0.container.stop()
            wait_for_good_bye(network.peers[0], context.node_startup_timeout * 3)
            engine0.start()
            node0.container.start()
            wait_for_node_started(network.peers[0], context.node_startup_timeout * 3, 2)
            wait_for_requested_for_fork_tip(network.peers[0], context.node_startup_timeout * 3, 2)
            wait_for_connected_to_node(network.bootstrap, network.peers[0].name, context.node_startup_timeout, 2)
            deploy(network.bootstrap, HELLO_NAME)
            hash_string = propose(network.bootstrap, HELLO_NAME)
            wait_for_sending_approved_block_request(network.bootstrap, network.peers[0].name, context.node_startup_timeout)
            wait_for_received_approved_block_request(network.bootstrap, network.peers[0].name, context.node_startup_timeout)
            wait_for_streamed_packet(network.bootstrap, network.peers[0].name, context.node_startup_timeout)
            wait_for_finalised_hash(network.bootstrap, hash_string, context.node_startup_timeout * 3)
            wait_for_finalised_hash(network.peers[0], hash_string, context.node_startup_timeout * 3)
            number_of_blocks = 1
            wait_for_metrics_and_assert_blocks_avaialable(network.peers[0], context.node_startup_timeout * 3, number_of_blocks)


def test_storage_after_multiple_node_deploy_propose_and_shutdown(two_node_network):
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

    assert dag0 == node0.vdag(10)
    assert dag1 == node1.vdag(10)
    assert blocks0 == node0.show_blocks_with_depth(10)
    assert blocks1 == node1.show_blocks_with_depth(10)
