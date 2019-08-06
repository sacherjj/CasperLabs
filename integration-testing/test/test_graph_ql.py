import requests

from test.cl_node.common import extract_deploy_hash_from_deploy_output
from test.cl_node.casperlabsnode import extract_block_hash_from_propose_output


def test_graphql(one_node_network):
    node = one_node_network.docker_nodes[0]
    deploy_output = node.client.deploy(session_contract='test_helloname.wasm',
                                       payment_contract='test_helloname.wasm')
    deploy_hash = extract_deploy_hash_from_deploy_output(deploy_output)
    propose_output = node.client.propose()
    block_hash = extract_block_hash_from_propose_output(propose_output)

    block_dict = node.graphql.query_block(block_hash)
    block = block_dict['data']['block']
    assert block['blockHash'] == block_hash
    assert block['deployCount'] == 1
    assert block['deployErrorCount'] == 0

    deploy_dict = node.graphql.query_deploy(deploy_hash)
    deploy = deploy_dict['data']['deploy']['deploy']
    processing_results = deploy_dict['data']['deploy']['processingResults']
    assert deploy['deployHash'] == deploy_hash
    assert processing_results[0]['block']['blockHash'] == block_hash
