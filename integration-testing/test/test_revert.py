import pytest


@pytest.fixture(scope='module')
def node(one_node_network_module_scope):
    return one_node_network_module_scope.docker_nodes[0]


@pytest.fixture(scope='module')
def client(node):
    return node.d_client


@pytest.fixture(scope='module')
def block_hash(node):
    return node.deploy_and_propose()


def test_revert(client, block_hash):
    r = client.show_deploys(block_hash)
    """
deploy {
  deploy_hash: "83e2433b8992b304f533690467433f00f1b90323ef62d1c3bfb953638c39a991"
  header {
    account_public_key: "3030303030303030303030303030303030303030303030303030303030303030"
    nonce: 1
    timestamp: 1560703757935
    gas_price: 0
    body_hash: "ee8c135766ee53fbee524cd98e052eb6aae2a3a3eb728cb8250911826e7c9715"
  }
}
cost: 14902
is_error: false
error_message: ""

    """
    # TODO:
    assert not r.is_error
    assert r.error_message == ''
    assert r.cost == 14902


    deploy_hash = r.deploy[0].deploy_hash
    r = client.show_deploy(deploy_hash)
    assert r.deploy[0].deploy_hash == deploy_hash

    """
------------- deploy # 79db3af60e / 0 ---------------
deploy {
  deploy_hash: "a5b7b84dc15bcf258927579de5a3625d16ecf3e45897735c2d8ad9bf1002c782"
  header {
    account_public_key: "3030303030303030303030303030303030303030303030303030303030303030"
    nonce: 1
    timestamp: 1560706651907
    gas_price: 0
    body_hash: "ee8c135766ee53fbee524cd98e052eb6aae2a3a3eb728cb8250911826e7c9715"
  }
}
cost: 14902
is_error: false
error_message: ""
---------------------------------------------------
count: 1
    """

