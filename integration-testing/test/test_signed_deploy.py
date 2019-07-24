from test.cl_node.casperlabsnode import extract_block_hash_from_propose_output
from test.cl_node.errors import NonZeroExitCodeError
import pytest

def test_deploy_with_valid_signature(one_node_network):
    """
    Feature file: deploy.feature
    Scenario: Deploy with valid signature
    """
    node0 = one_node_network.docker_nodes[0]
    client = node0.client
    client.deploy(session_contract='test_helloname.wasm',
                  payment_contract='test_helloname.wasm')

    block_hash = extract_block_hash_from_propose_output(client.propose())
    deploys = client.show_deploys(block_hash)
    assert deploys[0].is_error is False


def test_deploy_with_invalid_signature(one_node_network):
    """
    Feature file: deploy.feature
    Scenario: Deploy with invalid signature
    """

    node0 = one_node_network.docker_nodes[0]

    with pytest.raises(NonZeroExitCodeError):
        node0.client.deploy(session_contract='test_helloname.wasm',
                            payment_contract='test_helloname.wasm',
                            private_key="validator-0-private-invalid.pem",
                            public_key="validator-0-public-invalid.pem")
