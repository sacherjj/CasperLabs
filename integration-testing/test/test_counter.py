
def test_counter(one_node_network):

    node = one_node_network.docker_nodes[0]
    client = node.d_client

    def create_block(contract):
        return node.deploy_and_propose(session_contract=contract, payment_contract=contract)

    block_hash = create_block("test_counterdefine.wasm")
    r = client.query_state(block_hash=block_hash, key=node.from_address(), key_type="address", path="counter/count")
    assert r.int_value == 0
