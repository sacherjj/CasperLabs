
def test_mailinglist(one_node_network):

    node = one_node_network.docker_nodes[0]
    client = node.d_client

    def create_block(contract):
        block_hash = node.deploy_and_propose(session_contract=contract, payment_contract=contract)
        deploys = node.client.show_deploys(block_hash)
        for deploy in deploys:
            assert deploy.is_error is False
        return block_hash

    block_hash = create_block("test_mailinglistdefine.wasm")
    r = client.query_state(block_hash=block_hash, key=node.from_address, key_type="address", path="mailing/list")

    block_hash = create_block("test_mailinglistcall.wasm")
    r = client.query_state(block_hash=block_hash, key=node.from_address, key_type="address", path="mailing/list")
    assert r.string_list.values == 'CasperLabs'

    block_hash = create_block("test_mailinglistcall.wasm")
    r = client.query_state(block_hash=block_hash, key=node.from_address, key_type="address", path="mailing/list")
    assert r.string_list.values == 'CasperLabs'
