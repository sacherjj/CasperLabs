import pytest
from .cl_node.wait import wait_for_blocks_count_at_least

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

ACCOUNT = '30' * 32
assert ACCOUNT == "3030303030303030303030303030303030303030303030303030303030303030"

# Note: calls to wait_for_blocks_count_at_least should not really be needed in this test.
# The test network is just one node, we deploy and propose to this single node.
# Also, passing nonce explicitly is not really needed, as the test framework would pass a correct
# nonce for us, it's just to make it totally clear what's happenning.


@pytest.fixture(scope='module')
def node(one_node_network_module_scope):
    n = one_node_network_module_scope.docker_nodes[0]
    wait_for_blocks_count_at_least(n, 1, 1, n.timeout)
    return n


def test_account_state(node):
    def account_state():
        return node.d_client.queryState(blockHash = '', keyType = 'address', key = ACCOUNT, path = '')

    response = account_state()
    assert response.account.public_key == ACCOUNT
    assert response.account.nonce == 0

    node.deploy_and_propose(session_contract="test_counterdefine.wasm", nonce = 1)
    response = account_state()
    assert response.account.nonce == 1

    for nonce in range(2,10):
        node.deploy_and_propose(session_contract="test_countercall.wasm", nonce = nonce)
        wait_for_blocks_count_at_least(node, nonce + 1, nonce + 1, node.timeout)
        response = account_state()
        assert response.account.nonce == nonce

