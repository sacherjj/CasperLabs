import pytest

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


@pytest.fixture(scope='module')
def node(one_node_network_module_scope):
    return one_node_network_module_scope.docker_nodes[0]


def test_query_state_account(node):
    def account_state():
        return node.d_client.queryState(blockHash = '', keyType = 'address', key = ACCOUNT, path = '')

    response = account_state()
    assert response.account.public_key == ACCOUNT
    assert response.account.nonce == 0

    node.deploy_and_propose()

    response = account_state()
    assert response.account.nonce == 1

