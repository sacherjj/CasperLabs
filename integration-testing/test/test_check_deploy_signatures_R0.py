# fmt: off
import logging
import base64
import pytest

from casperlabs_local_net.common import Contract
from casperlabs_local_net.wait import wait_for_block_hash_propagated_to_all_nodes
from casperlabs_local_net import grpc_proxy
from casperlabs_local_net.grpc_proxy import (
    block_from_chunks,
    block_to_chunks,
    block_summary,
    block_justification,
    update_hashes_and_signature,
)
from casperlabs_client.utils import hexify


class GenerateBlockWithNoSignaturesGossipInterceptor(grpc_proxy.GossipInterceptor):
    """
    Sends a block with removed deploy signatures to node.

    GetBlockChunked is first intercepted to make a copy of a genuine block
    and modify it to remove deploy signatures. The original block is also added
    as justification of the new block so it is not considered an equivocation.

    Next, StreamLatestMessages is intercepted to advertise the new block.
    Node follows with StreamAncestorBlockSummaries, interceptor responds with
    summary of the new block.
    Eventually, node asks for the new block with GetBlockChunked and gets it
    from the interceptor.
    """

    def __init__(self, node):
        super().__init__(node)
        self.last_block = None
        self.new_block = None
        self.new_block_summary = None
        self.new_block_justification = None

    def pre_request(self, name, request):
        logging.info(f"GOSSIP PRE REQUEST: <= {name}({hexify(request)})")

        if name == "GetBlockChunked":
            if (
                self.new_block
                and request.block_hash == self.new_block.block_hash
            ):
                logging.info(
                    f"SENDING INVALID BLOCK {self.new_block.block_hash.hex()}"
                    f" to {self.node.config.number}"
                )
                return (
                    (chunk for chunk in block_to_chunks(self.new_block)),
                    None,
                )

        if name == "StreamAncestorBlockSummaries":
            if (
                self.new_block
                and self.new_block.block_hash == request.target_block_hashes[0]
            ):
                logging.info(
                    f"StreamAncestorBlockSummaries: {self.new_block_summary}"
                )
                return ((s for s in (self.new_block_summary,)), None)

        response, request = super().pre_request(name, request)
        return (response, request)

    def post_request_stream(self, name, request, response):
        logging.info(f"GOSSIP POST REQUEST STREAM: {name}({hexify(request)})")

        if name == "StreamLatestMessages":
            if self.new_block:
                b = self.new_block
                logging.info(
                    f"GOSSIP POST REQUEST STREAM: SENDING FAKE Justification with latest_block_hash ({b.block_hash.hex()}):"
                    f" {self.new_block_justification}"
                )
                yield self.new_block_justification
                return

        if name == "GetBlockChunked":
            if self.new_block:
                logging.info(
                    f"GOSSIP POST REQUEST STREAM: GetBlockChunked: sending invalid block"
                )
                for chunk in block_to_chunks(self.new_block):
                    yield chunk
                return
            else:
                logging.info(
                    f"GOSSIP POST REQUEST STREAM: Save block and generate new one"
                )
                response = [r for r in response]
                self.last_block = block_from_chunks(response)
                self.new_block = self.remove_signatures(block_from_chunks(response))
                self.new_block_summary = block_summary(self.new_block)
                self.new_block_justification = block_justification(
                    self.new_block
                )
                logging.info(
                    f"GOSSIP POST REQUEST STREAM: Invalid block hash: {self.new_block.block_hash.hex()}"
                )

        for r in response:
            logging.info(f"GOSSIP POST REQUEST STREAM: {name} => {hexify(r)[:100]}...")
            yield r

    def remove_signatures(self, block):
        """
        Remove approvals from first deploy in the block.
        Update timestamp and justifications so it is not an equivocation.
        """
        private_key = base64.b64decode(self.node.config.node_private_key)

        del block.body.deploys[0].deploy.approvals[:]
        block.header.validator_prev_block_hash = block.block_hash
        block.header.validator_block_seq_num += 1
        block.header.j_rank += 1
        block.header.justifications.extend([block_justification(block)])
        block.header.timestamp = block.header.timestamp + 1000  # 1 second later

        update_hashes_and_signature(block, private_key)
        return block


# TODO: Should match the new block downloading logic
# 1. Node A sends the 'GetBlockChunked' request with the 'exclude_deploy_bodies=true'.
# 2. Node B sends the block with stripped 'body' field in its deploys.
# 3. Node A:
#   a. Gathers deploy hashes from the block
#   b. Filters out deploys which Node A already has
#   c. Sends the 'StreamDeploysChunked' request with hashes of missing deploys
# 4. Node B sends the chunked 'DeploysList' message.
# 5. Node A restores the full block, validates and saves it as usual.
def test_check_deploy_signatures(intercepted_two_node_network):
    """
    Test node reject block with deploys that have no signatures.
    """
    nodes = intercepted_two_node_network.docker_nodes
    for node in nodes:
        node.proxy_server.set_interceptor(GenerateBlockWithNoSignaturesGossipInterceptor)

    node = nodes[0]
    account = node.genesis_account

    block_hash = node.deploy_and_get_block_hash(account, Contract.HELLO_NAME_DEFINE)
    logging.info(f"   =============> VALID BLOCK: {block_hash}")

    wait_for_block_hash_propagated_to_all_nodes(nodes, block_hash)

    block_hash = None
    if nodes[0].proxy_server.interceptor.new_block:
        block_hash = nodes[0].proxy_server.interceptor.new_block.block_hash
        receiver_node = nodes[1]
        logging.info(f"   =============> BLOCK WITH NO JUSTIFICATIONS: {block_hash.hex()}")

    with pytest.raises(Exception):
        wait_for_block_hash_propagated_to_all_nodes([receiver_node], block_hash.hex())
    assert "InvalidDeploySignature" in receiver_node.logs()
