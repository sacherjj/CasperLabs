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
    update_hashes_and_signature,
)
from casperlabs_client import (
    hexify,
    extract_common_name,
    node_pb2,
    gossiping_pb2 as gossiping,
)


class GenerateEquivocatingBlocksGossipInterceptor(grpc_proxy.GossipInterceptor):
    def __init__(self, node):
        super().__init__(node)
        self.last_block = None
        self.equivocating_block = None
        self.equivocating_block_summary = None

    def pre_request(self, name, request):
        """ ~/CasperLabs/protobuf/io/casperlabs/comm/gossiping/gossiping.proto
            ~/CasperLabs/protobuf/io/casperlabs/casper/consensus/consensus.proto
        """
        logging.info(f"GOSSIP PRE REQUEST: <= {name}({hexify(request)})")
        # method = getattr(self, name)
        # method(request)

        if name == "GetBlockChunked":
            """
            message GetBlockChunkedRequest {
                bytes block_hash = 1;
                uint32 chunk_size = 2;
                repeated string accepted_compression_algorithms = 3;
            }
            """
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

        if name == "StreamDagTipBlockSummaries":
            if self.equivocating_block:
                b = self.equivocating_block
                logging.info(
                    f"GOSSIP POST REQUEST STREAM: SENDING FAKE block summary ({b.block_hash.hex()})"
                )
                yield self.equivocating_block_summary
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
                self.equivocating_block = block_from_chunks(response)
                self.equivocating_block = self.modify_to_equivocate(
                    self.equivocating_block
                )
                self.equivocating_block_summary = block_summary(self.equivocating_block)
                logging.info(
                    f"GOSSIP POST REQUEST STREAM: Equivocating block hash: {self.equivocating_block.block_hash.hex()}"
                )

        for r in response:
            logging.info(f"GOSSIP POST REQUEST STREAM: {name} => {hexify(r)[:1000]}...")
            yield r

    def modify_to_equivocate(self, block):
        private_key = base64.b64decode(self.node.config.node_private_key)
        block.header.timestamp = block.header.timestamp + 1000  # 1 second later
        update_hashes_and_signature(block, private_key)
        return block


def test_equivocation(intercepted_two_node_network):
    """
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

    # Inject equivocating block.
    # Pretend a node is advertising our equivocating block.
    block_hash = None
    if nodes[0].proxy_server.interceptor.equivocating_block:
        logging.info(f"   =============> ON NODE [0]")
        block_hash = nodes[0].proxy_server.interceptor.equivocating_block.block_hash
        sender_node = nodes[0]
        receiver_node = nodes[1]
    if nodes[1].proxy_server.interceptor.equivocating_block:
        logging.info(f"   =============> ON NODE [1]")
        block_hash = nodes[1].proxy_server.interceptor.equivocating_block.block_hash
        sender_node = nodes[1]
        receiver_node = nodes[0]

    logging.info(
        f"   =============> EQUIVOCATING BLOCK: {block_hash and block_hash.hex()}"
    )
    sender = node_pb2.Node(
        id=bytes.fromhex(sender_node.node_id),
        host=sender_node.node_host,
        protocol_port=sender_node.server_proxy_port,
        discovery_port=sender_node.kademlia_proxy_port,
    )
    new_blocks_request = gossiping.NewBlocksRequest(
        sender=sender, block_hashes=[block_hash]
    )
    try:
        receiver_node.proxy_server.servicer.update_credentials(
            sender_node.config.tls_certificate_local_path(),
            sender_node.config.tls_key_local_path(),
        )
        response = receiver_node.proxy_server.service.NewBlocks(new_blocks_request)
        logging.info(
            f"   === FAKE NewBlocksRequest({block_hash.hex()}) ==> {hexify(response)}"
        )
    except Exception as ex:
        logging.warning(f"   === EXCEPTION: {str(ex)}")

    wait_for_block_hash_propagated_to_all_nodes([receiver_node], block_hash.hex())
    assert "Found equivocation:" in receiver_node.logs()
