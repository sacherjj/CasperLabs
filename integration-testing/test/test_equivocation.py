# fmt: off
import logging
import base64

from casperlabs_local_net.cli import DockerCLI
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
from casperlabs_client.utils import hexify, extract_common_name


class GenerateEquivocatingBlocksGossipInterceptor(grpc_proxy.GossipInterceptor):
    """
    Sends an equivocating block to node.

    GetBlockChunked is first intercepted to make a copy of a genuine block
    and modify it so it has a different identity but the same justifications.
    Next, StreamLatestMessages is intercepted to advertise the equivocating block.
    Node follows with StreamAncestorBlockSummaries, interceptor responds with
    summary of the equivocating block.
    Eventually, node asks for the equivocating block with GetBlockChunked and gets it
    from the interceptor.
    """

    def __init__(self, node):
        super().__init__(node)
        self.last_block = None
        self.equivocating_block = None
        self.equivocating_block_summary = None
        self.equivocating_block_justification = None

    def pre_request(self, name, request):
        logging.info(f"GOSSIP PRE REQUEST: <= {name}({hexify(request)})")

        if name == "GetBlockChunked":
            if (
                self.equivocating_block
                and request.block_hash == self.equivocating_block.block_hash
            ):
                logging.info(
                    f"SENDING EQUIVOCATING BLOCK {self.equivocating_block.block_hash.hex()}"
                    f" to {self.node.config.number}"
                )
                return (
                    (chunk for chunk in block_to_chunks(self.equivocating_block)),
                    None,
                )

        if name == "StreamAncestorBlockSummaries":
            if (
                self.equivocating_block
                and self.equivocating_block.block_hash == request.target_block_hashes[0]
            ):
                logging.info(
                    f"StreamAncestorBlockSummaries: {self.equivocating_block_summary}"
                )
                return ((s for s in (self.equivocating_block_summary,)), None)

        response, request = super().pre_request(name, request)
        return (response, request)

    def post_request_stream(self, name, request, response):
        logging.info(f"GOSSIP POST REQUEST STREAM: {name}({hexify(request)})")

        if name == "StreamLatestMessages":
            if self.equivocating_block:
                b = self.equivocating_block
                logging.info(
                    f"GOSSIP POST REQUEST STREAM: SENDING FAKE Justification with latest_block_hash ({b.block_hash.hex()}):"
                    f" {self.equivocating_block_justification}"
                )
                yield self.equivocating_block_justification
                return

        if name == "GetBlockChunked":
            if self.equivocating_block:
                logging.info(
                    f"GOSSIP POST REQUEST STREAM: GetBlockChunked: sending FAKE block"
                )
                for chunk in block_to_chunks(self.equivocating_block):
                    yield chunk
                return
            else:
                logging.info(
                    f"GOSSIP POST REQUEST STREAM: Save block and generate equivocating one"
                )
                response = [r for r in response]
                self.last_block = block_from_chunks(response)
                self.equivocating_block = self.modify_to_equivocate(
                    block_from_chunks(response)
                )
                self.equivocating_block_summary = block_summary(self.equivocating_block)
                self.equivocating_block_justification = block_justification(
                    self.equivocating_block
                )
                logging.info(
                    f"GOSSIP POST REQUEST STREAM: Equivocating block hash: {self.equivocating_block.block_hash.hex()}"
                )

        for r in response:
            logging.info(f"GOSSIP POST REQUEST STREAM: {name} => {hexify(r)[:100]}...")
            yield r

    def modify_to_equivocate(self, block):
        """
        Create a clone of the given block with just its timestamp modified.
        """
        private_key = base64.b64decode(self.node.config.node_private_key)
        block.header.timestamp = block.header.timestamp + 1000  # 1 second later
        update_hashes_and_signature(block, private_key)
        return block


def test_equivocation(intercepted_two_node_network):
    """
    Generate an equivocating block and check the system can detect it.
    """
    nodes = intercepted_two_node_network.docker_nodes
    for node in nodes:
        node.proxy_server.set_interceptor(GenerateEquivocatingBlocksGossipInterceptor)

    node = nodes[0]
    account = node.genesis_account

    tls_certificate_path = node.config.tls_certificate_local_path()
    tls_parameters = {"--node-id": extract_common_name(tls_certificate_path)}

    cli = DockerCLI(nodes[0], tls_parameters=tls_parameters)
    cli.set_default_deploy_args(
        "--from",
        account.public_key_hex,
        "--private-key",
        cli.private_key_path(account),
        "--public-key",
        cli.public_key_path(account),
        "--payment",
        cli.resource(Contract.STANDARD_PAYMENT),
        "--payment-args",
        cli.payment_json,
    )
    cli("deploy", "--session", cli.resource(Contract.HELLO_NAME_DEFINE))
    block_hash = cli("propose")
    logging.info(f"   =============> REAL BLOCK: {block_hash}")

    wait_for_block_hash_propagated_to_all_nodes(nodes, block_hash)

    block_hash = None
    if nodes[0].proxy_server.interceptor.equivocating_block:
        block_hash = nodes[0].proxy_server.interceptor.equivocating_block.block_hash
        receiver_node = nodes[1]
        logging.info(f"   =============> EQUIVOCATING BLOCK: {block_hash.hex()}")

    wait_for_block_hash_propagated_to_all_nodes([receiver_node], block_hash.hex())
    assert "Found equivocation:" in receiver_node.logs()
