from test.cl_node.casperlabsnode import HELLO_NAME


def test_newly_joined_node_should_not_gossip_blocks(node_join_existing_network):
    """
    Feature file: node_added_to_established_network.feature
    Scenario: New node should not gossip old blocks back to network
    """
    block_hashes = []
    for node in node_join_existing_network.docker_nodes:
        block_hashes.append(node.deploy_and_propose(session_contract=HELLO_NAME,
                                                    private_key="validator-0-private.pem",
                                                    public_key="validator-0-public.pem"))
    node_join_existing_network.add_node_to_existing_network()
    node0, node1, node2 = node_join_existing_network.docker_nodes
    for block in block_hashes:
        assert f"Attempting to add Block {block}... to DAG" in node2.logs()