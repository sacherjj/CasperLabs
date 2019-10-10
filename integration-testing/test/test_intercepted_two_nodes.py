import logging
import lz4.block
import ed25519
import base64
from pytest import raises

from casperlabs_local_net.cli import DockerCLI
from casperlabs_local_net.common import Contract
from casperlabs_local_net.wait import wait_for_block_hash_propagated_to_all_nodes
from casperlabs_local_net import grpc_proxy
from casperlabs_client import (
    hexify,
    extract_common_name,
    blake2b_hash,
    consensus_pb2 as consensus,
    gossiping_pb2 as gossiping,
    node_pb2,
)


def block_from_chunks(chunks):
    """Builds Block from chunks returned from GetBlockChunked"""
    # TODO: handle more than one data chunk
    chunks = list(chunks)
    header_chunk, data_chunk = chunks

    uncompressed_block_data = lz4.block.decompress(
        data_chunk.data, uncompressed_size=header_chunk.header.original_content_length
    )
    block = consensus.Block()
    block.ParseFromString(uncompressed_block_data)
    return block


def block_to_chunks(block):
    data = block.SerializeToString()
    compressed_data = lz4.block.compress(data, store_size=False)

    header_chunk = gossiping.Chunk(
        header=gossiping.Chunk.Header(
            compression_algorithm="lz4",
            content_length=len(compressed_data),
            original_content_length=len(data),
        )
    )
    data_chunk = gossiping.Chunk(data=compressed_data)
    return [header_chunk, data_chunk]


def update_hashes_and_signature(block, private_key):
    """Updates in-place block.header.body_hash, block.block_hash and block.signature."""
    block.header.body_hash = blake2b_hash(block.body.SerializeToString())
    block_hash = blake2b_hash(block.header.SerializeToString())
    block.block_hash = block_hash

    block.signature.sig_algorithm = "ed25519"
    block.signature.sig = ed25519.SigningKey(private_key).sign(block_hash)
    return block


class RemoveSignatureGossipInterceptor(grpc_proxy.GossipInterceptor):
    def post_request_stream(self, name, request, response):
        logging.info(f"GOSSIP POST REQUEST STREAM: {name}({hexify(request)})")

        if name == "GetBlockChunked":
            block = block_from_chunks(response)

            # Remove approvals from first deploy in the block.
            del block.body.deploys[0].deploy.approvals[:]

            private_key = base64.b64decode(self.node.config.node_private_key)
            update_hashes_and_signature(block, private_key)
            response = block_to_chunks(block)

        for r in response:
            logging.info(f"GOSSIP POST REQUEST STREAM: {name} => {hexify(r)}")
            yield r


def test_check_deploy_signatures(intercepted_two_node_network):
    """
    This tests uses an interceptor that modifies block retrieved
    by node-1 from node-0 with GetBlockChunked method of the gossip service
    and removes approvals from deploys in the block.
    node-1 should not accept this block.
    """
    nodes = intercepted_two_node_network.docker_nodes
    for node in nodes:
        node.proxy_server.set_interceptor(RemoveSignatureGossipInterceptor)
    node = nodes[0]
    account = node.genesis_account

    tls_certificate_path = node.config.tls_certificate_local_path()
    tls_parameters = {
        # Currently only Python client requires --certificate-file
        # It may not need it in the future.
        # "--certificate-file": tls_certificate_path,
        "--node-id": extract_common_name(tls_certificate_path)
    }

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

    with raises(Exception):
        wait_for_block_hash_propagated_to_all_nodes(nodes, block_hash)

    assert "InvalidDeploySignature" in nodes[1].logs()


class GenerateEquivocatingBlocksGossipInterceptor(grpc_proxy.GossipInterceptor):
    def __init__(self, node):
        super().__init__(node)
        self.last_block = None
        self.equivocating_block = None

    def pre_request(self, name, request):
        """ ~/CasperLabs/protobuf/io/casperlabs/comm/gossiping/gossiping.proto """

        logging.info(f"GOSSIP PRE REQUEST: <= {name}({hexify(request)})")

        request = super().pre_request(name, request)

        return request

    def post_request_stream(self, name, request, response):
        logging.info(f"GOSSIP POST REQUEST STREAM: {name}({hexify(request)})")

        if name == "GetBlockChunked":
            response = [r for r in response]
            self.last_block = block_from_chunks(response)
            self.equivocating_block = self.generate_equivocating_block(self.last_block)

        for r in response:
            logging.info(f"GOSSIP POST REQUEST STREAM: {name} => {hexify(r)}")
            yield r

    def generate_equivocating_block(self, block):
        nodes = self.node.cl_network.docker_nodes
        other_node = nodes[(self.node.config.number + 1) % len(nodes)]

        private_key = base64.b64decode(other_node.config.node_private_key)
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

    block_hash = bytes(range(32))
    sender = node_pb2.Node(
        id=bytes.fromhex(node.node_id),
        host=node.node_host,
        protocol_port=node.server_proxy_port,
        discovery_port=node.kademlia_proxy_port,
    )

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

    wait_for_block_hash_propagated_to_all_nodes(nodes, block_hash)

    block_hash = node.proxy_server.interceptor.equivocating_block.block_hash
    response = node.proxy_server.service.NewBlocks(
        gossiping.NewBlocksRequest(sender=sender, block_hashes=[block_hash])
    )
    logging.info(f"   =============> {hexify(response)}")
