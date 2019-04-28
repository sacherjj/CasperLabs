from . import conftest
from .cl_node.casperlabsnode import (
    CONTRACT_NAME,
    complete_network,
    deploy_and_propose,
)
from .cl_node.wait import (
    wait_for_received_approved_block_request,
    wait_for_sending_approved_block_request,
    wait_for_streamed_packet,
    wait_for_node_started,
    wait_for_metrics_and_assert_blocks_avaialable,
    wait_for_connected_to_node,
    wait_for_requested_for_fork_tip,
    wait_for_good_bye,
    wait_for_finalised_hash,
)


def test_persistent_dag_store(command_line_options_fixture, docker_client_fixture):
    with conftest.testing_context(
        command_line_options_fixture,
        docker_client_fixture,
        peers_keypairs=[]  # will create a single peer network
    ) as context:
        with complete_network(context) as network:
            for node in network.nodes:
                deploy_and_propose(node, CONTRACT_NAME)
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
            hash_string = deploy_and_propose(network.bootstrap, CONTRACT_NAME)
            wait_for_sending_approved_block_request(network.bootstrap, network.peers[0].name, context.node_startup_timeout)
            wait_for_received_approved_block_request(network.bootstrap, network.peers[0].name, context.node_startup_timeout)
            wait_for_streamed_packet(network.bootstrap, network.peers[0].name, context.node_startup_timeout)
            wait_for_finalised_hash(network.bootstrap, hash_string, context.node_startup_timeout * 3)
            wait_for_finalised_hash(network.peers[0], hash_string, context.node_startup_timeout * 3)
            wait_for_metrics_and_assert_blocks_avaialable(network.peers[0], context.node_startup_timeout * 3)
