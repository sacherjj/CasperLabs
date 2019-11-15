from casperlabs_local_net.common import (
    extract_deploy_hash_from_deploy_output,
    extract_block_hash_from_propose_output,
    Contract,
)
from casperlabs_local_net.wait import wait_for_block_hash_propagated_to_all_nodes
from casperlabs_local_net.casperlabs_accounts import GENESIS_ACCOUNT


def test_graph_ql(one_node_network):
    node = one_node_network.docker_nodes[0]
    deploy_output = node.d_client.deploy(
        session_contract=Contract.HELLO_NAME_DEFINE,
        from_address=GENESIS_ACCOUNT.public_key_hex,
        public_key=GENESIS_ACCOUNT.public_key_path,
        private_key=GENESIS_ACCOUNT.private_key_path,
    )
    deploy_hash = extract_deploy_hash_from_deploy_output(deploy_output)
    propose_output = node.client.propose()
    block_hash = extract_block_hash_from_propose_output(propose_output)

    wait_for_block_hash_propagated_to_all_nodes(
        one_node_network.docker_nodes, block_hash
    )

    block_dict = node.graphql.query_block(block_hash)
    block = block_dict["data"]["block"]
    assert block["blockHash"] == block_hash
    assert block["deployCount"] == 1
    assert block["deployErrorCount"] == 0

    deploy_dict = node.graphql.query_deploy(deploy_hash)
    deploy = deploy_dict["data"]["deploy"]["deploy"]
    processing_results = deploy_dict["data"]["deploy"]["processingResults"]
    assert deploy["deployHash"] == deploy_hash
    assert processing_results[0]["block"]["blockHash"] == block_hash
