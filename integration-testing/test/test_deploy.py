
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


def test_deploy_with_valid_signature(one_node_network):
    """
    Feature file: deploy.feature
    Scenario: Deploy with valid signature
    """
    node0 = one_node_network.docker_nodes[0]
    node0.deploy()


def test_deploy_with_invalid_signature(one_node_network):
    """
    Feature file: deploy.feature
    Scenario: Deploy with invalid signature
    """
    node0 = one_node_network.docker_nodes[0]
    node0.deploy()