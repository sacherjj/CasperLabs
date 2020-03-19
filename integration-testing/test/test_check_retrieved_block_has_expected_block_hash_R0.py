import logging
import base64
from pytest import raises

from casperlabs_local_net.common import Contract
from casperlabs_local_net.wait import wait_for_block_hash_propagated_to_all_nodes
from casperlabs_local_net import grpc_proxy
from casperlabs_local_net.grpc_proxy import (
    block_from_chunks,
    block_to_chunks,
    update_hashes_and_signature,
)
from casperlabs_client.utils import hexify


class RemoveSignatureGossipInterceptor(grpc_proxy.GossipInterceptor):
    def post_request_stream(self, name, request, response):
        logging.debug(f"GOSSIP POST REQUEST STREAM: {name}({hexify(request)})")

        if name == "GetBlockChunked":
            block = block_from_chunks(response)

            # Remove approvals from first deploy in the block.
            del block.body.deploys[0].deploy.approvals[:]

            private_key = base64.b64decode(self.node.config.node_private_key)
            update_hashes_and_signature(block, private_key)
            response = block_to_chunks(block)

        for r in response:
            logging.debug(f"GOSSIP POST REQUEST STREAM: {name} => {hexify(r)}")
            yield r


def test_check_retrieved_block_has_expected_block_hash(intercepted_two_node_network):
    """
    This test uses an interceptor that modifies block retrieved
    by node-1 from node-0 with GetBlockChunked method of the gossip service
    and removes approvals from deploys in the block.

    The test originally checked that the block was rejected by the node
    due to no having the required signatures.

    Now, however, it is rejected because the modification changes the block's hash.
    Currently node rejects the block because it has a different hash than requested.
    """
    nodes = intercepted_two_node_network.docker_nodes
    for node in nodes:
        node.proxy_server.set_interceptor(RemoveSignatureGossipInterceptor)
    node = nodes[0]
    account = node.genesis_account

    block_hash = node.deploy_and_get_block_hash(account, Contract.HELLO_NAME_DEFINE)

    with raises(Exception):
        wait_for_block_hash_propagated_to_all_nodes(nodes, block_hash)

    assert "Retrieved block has unexpected" in nodes[1].logs()
