import pytest
from .cl_node.client_parser import parse_show_blocks

"""
Test account state retrieval with query-state.

Example output of the Scala client:

account {
  public_key: "3030303030303030303030303030303030303030303030303030303030303030"
  nonce: 1
  purse_id {
    uref: "0000000000000000000000000000000000000000000000000000000000000000"
    access_rights: READ_ADD_WRITE
  }
  associated_keys {
    public_key: "3030303030303030303030303030303030303030303030303030303030303030"
    weight: 1
  }
  action_thresholds {
    deployment_threshold: 1
    key_management_threshold: 1
  }
  account_activity {
    key_management_last_used: 0
    deployment_last_used: 0
    inactivity_period_limit: 100
  }
}

"""

@pytest.fixture(scope='module')
def node(one_node_network_module_scope):
    n = one_node_network_module_scope.docker_nodes[0]
    return n


def test_account_state(node):

    def account_state(block_hash):
        return node.d_client.query_state(block_hash = block_hash, key_type = 'address', key = node.from_address(), path = '')

    blocks = parse_show_blocks(node.d_client.show_blocks(1000))
    assert len(blocks) == 1  # There should be only one block, the genesis block

    response = account_state(blocks[0].summary.block_hash)
    assert response.account.nonce == 0

    block_hash = node.deploy_and_propose(session_contract = "test_counterdefine.wasm", nonce = 1)
    deploys = node.client.show_deploys(block_hash)
    assert not deploys[0].is_error, str(deploys)

    response = account_state(block_hash)
    assert response.account.nonce == 1, str(response)

    for nonce in range(2, 5):
        block_hash = node.deploy_and_propose(session_contract="test_countercall.wasm", nonce = nonce)
        response = account_state(block_hash)
        assert response.account.nonce == nonce

