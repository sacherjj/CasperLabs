
def deploy_and_propose_expect_no_errors(node, contract):
    client = node.d_client

    block_hash = node.deploy_and_propose(session_contract=contract, payment_contract=contract)
    r = client.show_deploys(block_hash)[0]
    assert not r.is_error
    assert r.error_message == ''


def test_get_caller(one_node_network):
    node = one_node_network.docker_nodes[0]

    deploy_and_propose_expect_no_errors(node, 'getcallerdefine.wasm')
    deploy_and_propose_expect_no_errors(node, 'getcallercall.wasm')

