
from test import conftest
from test.cl_node.casperlabsnode import (
    HELLO_NAME,
    HELLO_WORLD,
    complete_network,
    deploy,
    propose,
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