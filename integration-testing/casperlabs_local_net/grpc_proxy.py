#!/usr/bin/env python3
import grpc
from concurrent import futures
from threading import Thread
import logging
import re
from casperlabs_client import (
    hexify,
    casper_pb2_grpc,
    gossiping_pb2_grpc,
    kademlia_pb2_grpc,
    extract_common_name,
    blake2b_hash,
    consensus_pb2 as consensus,
    gossiping_pb2 as gossiping,
)
import ed25519
import lz4.block


def read_binary(file_name):
    with open(file_name, "rb") as f:
        return f.read()


def fun_name(o):
    if not o:
        return str(o)
    return str(o).split()[1]


def stub_name(o):
    s = list(filter(None, re.split(r"\W+", str(o))))[-1]
    return s[: -len("ServiceStub")]


class Interceptor:
    def __init__(self, node):
        self.node = node

    def __str__(self):
        return "interceptor"

    def pre_request(self, name, request):
        """
        Preprocess request received by the proxy.
        Returns tuple (response, request). If response is not None it will be returned to the client
        and the node behind proxy will not be convnected to at all.
        """
        return (None, request)

    def post_request(self, name, request, response):
        """
        Postprocess response from node received by the proxy before sending it back to the requesting node.
        """
        return response

    def post_request_stream(self, name, request, response):
        """
        Postprocess stream response from node received by the proxy before sending it back to the requesting node.
        """
        yield from response


class LoggingInterceptor(Interceptor):
    def __str__(self):
        return "logging_interceptor"

    def pre_request(self, name, request):
        logging.info(f"PRE REQUEST: {name}({hexify(request)})")
        return (None, request)

    def post_request(self, name, request, response):
        logging.info(f"POST REQUEST: {name}({hexify(request)}) => ({hexify(response)})")
        return response

    def post_request_stream(self, name, request, response):
        logging.info(f"POST REQUEST STREAM: {name}({hexify(request)}) => {response}")
        yield from response


class KademliaInterceptor(Interceptor):
    """ ~/CasperLabs/protobuf/io/casperlabs/comm/discovery/kademlia.proto """

    def __str__(self):
        return "kademlia_interceptor"

    def pre_request(self, name, request):
        logging.info(f"KADEMLIA PRE REQUEST: <= {name}({hexify(request)})")

        """
        Patch node address to point to proxy.

         sender {
           id: "4c191022c23629572e317c986498a7c054cc9038"
           host: "node-1-eiaqe-test"
           protocol_port: 50400
           discovery_port: 50404
         }

        """
        node = self.node.cl_network.lookup_node(request.sender.id.hex())
        request.sender.host = node.proxy_host
        request.sender.protocol_port = node.server_proxy_port
        request.sender.discovery_port = node.kademlia_proxy_port

        logging.info(f"KADEMLIA PRE REQUEST: => {name}({hexify(request)})")
        return (None, request)

    def post_request(self, name, request, response):
        logging.info(
            f"KADEMLIA POST REQUEST: {name}({hexify(request)}) => ({hexify(response)})"
        )
        return response

    def post_request_stream(self, name, request, response):
        logging.info(
            f"KADEMLIA POST REQUEST STREAM: {name}({hexify(request)}) => {response}"
        )
        for r in response:
            node = self.node.cl_network.lookup_node(r.id.hex())
            r.host = node.proxy_host
            r.protocol_port = node.server_proxy_port
            r.discovery_port = node.kademlia_proxy_port
            logging.info(f"KADEMLIA POST REQUEST STREAM: {name} => {r}")
            yield r


class GossipInterceptor(Interceptor):
    def __str__(self):
        return "gossip_interceptor"

    def pre_request(self, name, request):
        """ ~/CasperLabs/protobuf/io/casperlabs/comm/gossiping/gossiping.proto """

        logging.info(f"GOSSIP PRE REQUEST: <= {name}({hexify(request)})")

        if name == "NewBlocks":
            """
            NewBlocks(sender {
               id: "4c191022c23629572e317c986498a7c054cc9038"
               host: "node-1-kxtcw-test"
               protocol_port: 50400
               discovery_port: 50404
            }
            block_hashes: "0f449d2ae52139bda1a201a22d6f142ca4ae616b92867b301f1c6244f08defbb"
            )
            """
            node = self.node.cl_network.lookup_node(request.sender.id.hex())
            request.sender.host = node.proxy_host
            request.sender.protocol_port = node.server_proxy_port
            request.sender.discovery_port = node.kademlia_proxy_port

            # Update SSL credentials for the proxy connection to server node
            try:
                self.node.proxy_server.servicer.update_credentials(
                    node.config.tls_certificate_local_path(),
                    node.config.tls_key_local_path(),
                )
                logging.info(
                    f"GOSSIP PRE REQUEST: ({self.node.node_id}, {self.node.container_name})"
                    f"update_credentials with:"
                    f" {node.node_id} {node.container_name}"
                )
            except Exception as ex:
                logging.info(f"GOSSIP PRE REQUEST: FAILED UPDATING CREDS: {str(ex)}")

            logging.info(f"GOSSIP PRE REQUEST: => {name}({hexify(request)})")

        return (None, request)

    def post_request(self, name, request, response):
        logging.info(
            f"GOSSIP POST REQUEST: {name}({hexify(request)}) => ({hexify(response)})"
        )
        return response

    def post_request_stream(self, name, request, response):
        logging.info(f"GOSSIP POST REQUEST STREAM: {name}({hexify(request)})")
        for r in response:
            logging.info(f"GOSSIP POST REQUEST STREAM: {name} => {hexify(r)}")
            yield r


class ProxyServicer:
    def __init__(
        self,
        node_host: str = None,
        node_port: int = None,
        proxy_port: int = None,  # just for better logging
        encrypted_connection: bool = True,
        certificate_file: str = None,
        key_file: str = None,
        node_id: str = None,
        service_stub=None,
        interceptor: LoggingInterceptor = None,
    ):
        self.node_host = node_host
        self.node_port = node_port
        self.proxy_port = proxy_port
        self.certificate_file = certificate_file
        self.key_file = key_file
        self.node_id = node_id
        self.service_stub = service_stub
        self.interceptor = interceptor
        self.node_address = f"{self.node_host}:{self.node_port}"

        self.encrypted_connection = encrypted_connection
        if self.encrypted_connection:
            self.update_credentials(certificate_file, key_file, node_id)

        self.log_prefix = (
            f"PROXY"
            f" {self.node_host}:{self.node_port} on {self.proxy_port}"
            f" {stub_name(self.service_stub)}"
        )
        logging.info(f"{self.log_prefix}, interceptor: {self.interceptor}")

    def update_credentials(self, certificate_file, key_file, node_id=None):
        self.node_id = node_id or extract_common_name(certificate_file)
        self.certificate_file = certificate_file
        self.key_file = key_file
        self.credentials = grpc.ssl_channel_credentials(
            root_certificates=read_binary(certificate_file),
            private_key=read_binary(key_file),
            certificate_chain=read_binary(certificate_file),
        )
        self.secure_channel_options = self.node_id and [
            ("grpc.ssl_target_name_override", self.node_id),
            ("grpc.default_authority", self.node_id),
        ]

    def channel(self):
        return (
            self.encrypted_connection
            and grpc.secure_channel(
                self.node_address, self.credentials, options=self.secure_channel_options
            )
            or grpc.insecure_channel(self.node_address)
        )

    def is_unary_stream(self, method_name):
        return method_name.startswith("Stream") or method_name.endswith("Chunked")

    def __getattr__(self, name):
        """ Implement the gRPC Server's Servicer interface. """

        def unary_unary(request, context):
            logging.info(f"{self.log_prefix}: ({hexify(request)})")
            with self.channel() as channel:
                response, preprocessed_request = self.interceptor.pre_request(
                    name, request
                )
                if response:
                    return response
                service_method = getattr(self.service_stub(channel), name)
                response = service_method(preprocessed_request)
                return self.interceptor.post_request(
                    name, preprocessed_request, response
                )

        def unary_stream(request, context):
            logging.info(f"{self.log_prefix}: ({hexify(request)})")
            with self.channel() as channel:
                response, preprocessed_request = self.interceptor.pre_request(
                    name, request
                )
                if response:
                    yield from response
                else:
                    streaming_service_method = getattr(self.service_stub(channel), name)
                    response_stream = streaming_service_method(preprocessed_request)
                    yield from self.interceptor.post_request_stream(
                        name, preprocessed_request, response_stream
                    )

        return unary_stream if self.is_unary_stream(name) else unary_unary

    @property
    def service(self):
        """ Provide client API for the service behind proxy. """

        class Service:
            def __init__(self, servicer):
                self.servicer = servicer

            def __getattr__(self, name):
                def is_unary_stream(method_name):
                    return method_name.startswith("Stream") or method_name.endswith(
                        "Chunked"
                    )

                def unary_stream(*args, **kwargs):
                    with self.servicer.channel() as channel:
                        method = getattr(self.servicer.service_stub(channel), name)
                        yield from method(*args, *kwargs)

                def unary_unary(*args, **kwargs):
                    with self.servicer.channel() as channel:
                        method = getattr(self.servicer.service_stub(channel), name)
                        return method(*args, *kwargs)

                return is_unary_stream(name) and unary_stream or unary_unary

        return Service(self)


class ProxyThread(Thread):
    def __init__(
        self,
        service_stub,
        add_servicer_to_server,
        node_host: str,
        node_port: int,
        proxy_port: int,
        encrypted_connection: bool = True,
        server_certificate_file: str = None,
        server_key_file: str = None,
        client_certificate_file: str = None,
        client_key_file: str = None,
        interceptor=None,
    ):
        super().__init__()
        self.service_stub = service_stub
        self.add_servicer_to_server = add_servicer_to_server
        self.node_host = node_host
        self.node_port = node_port
        self.proxy_port = proxy_port
        self.server_certificate_file = server_certificate_file
        self.server_key_file = server_key_file
        self.client_certificate_file = client_certificate_file
        self.client_key_file = client_key_file
        self.encrypted_connection = encrypted_connection
        self.node_id = None
        if self.encrypted_connection:
            self.node_id = extract_common_name(self.client_certificate_file)
        self.servicer = ProxyServicer(
            node_host=self.node_host,
            node_port=self.node_port,
            proxy_port=self.proxy_port,
            encrypted_connection=self.encrypted_connection,
            certificate_file=self.client_certificate_file,
            key_file=self.client_key_file,
            node_id=self.node_id,
            service_stub=self.service_stub,
            interceptor=interceptor,
        )

    def set_interceptor(self, interceptor_class):
        node = self.servicer.interceptor.node
        self.servicer.interceptor = interceptor_class(node)

    @property
    def interceptor(self):
        return self.servicer.interceptor

    def run(self):
        self.server = grpc.server(futures.ThreadPoolExecutor(max_workers=1))
        self.add_servicer_to_server(self.servicer, self.server)
        self.port = f"[::]:{self.proxy_port}"
        if not self.encrypted_connection:
            self.server.add_insecure_port(self.port)
        else:
            self.server.add_secure_port(
                self.port,
                grpc.ssl_server_credentials(
                    [
                        (
                            read_binary(self.server_key_file),
                            read_binary(self.server_certificate_file),
                        )
                    ]
                ),
            )
        logging.info(
            f"STARTING PROXY: node={self.node_host}:{self.node_port} proxy_port={self.proxy_port}"
        )
        self.server.start()

    def stop(self):
        logging.info(
            f"STOPPING PROXY: node={self.node_host}:{self.node_port} proxy_port={self.proxy_port}"
        )
        self.server.stop(0)

    @property
    def service(self):
        """"Gives access to the gRPC API of the node behind proxy."""
        return self.servicer.service


def proxy_client(
    node,
    node_port=40401,
    node_host="casperlabs",
    proxy_port=50401,
    server_certificate_file: str = None,
    server_key_file: str = None,
    client_certificate_file: str = None,
    client_key_file: str = None,
    interceptor_class=LoggingInterceptor,
):
    t = ProxyThread(
        casper_pb2_grpc.CasperServiceStub,
        casper_pb2_grpc.add_CasperServiceServicer_to_server,
        proxy_port=proxy_port,
        node_host=node_host,
        node_port=node_port,
        server_certificate_file=server_certificate_file,
        server_key_file=server_key_file,
        client_certificate_file=client_certificate_file,
        client_key_file=client_key_file,
        interceptor=interceptor_class(node),
    )
    t.start()
    return t


def proxy_server(
    node,
    node_port=50400,
    node_host="localhost",
    proxy_port=40400,
    server_certificate_file=None,
    server_key_file=None,
    client_certificate_file=None,
    client_key_file=None,
    interceptor_class=GossipInterceptor,
):
    t = ProxyThread(
        gossiping_pb2_grpc.GossipServiceStub,
        gossiping_pb2_grpc.add_GossipServiceServicer_to_server,
        proxy_port=proxy_port,
        node_host=node_host,
        node_port=node_port,
        server_certificate_file=server_certificate_file,
        server_key_file=server_key_file,
        client_certificate_file=client_certificate_file,
        client_key_file=client_key_file,
        interceptor=interceptor_class(node),
    )
    t.start()
    return t


def proxy_kademlia(
    node,
    node_port=50404,
    node_host="127.0.0.1",
    proxy_port=40404,
    interceptor_class=KademliaInterceptor,
):
    t = ProxyThread(
        kademlia_pb2_grpc.KademliaServiceStub,
        kademlia_pb2_grpc.add_KademliaServiceServicer_to_server,
        proxy_port=proxy_port,
        node_host=node_host,
        node_port=node_port,
        encrypted_connection=False,
        interceptor=interceptor_class(node),
    )
    t.start()
    return t


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


def block_summary(block):
    return consensus.BlockSummary(
        block_hash=block.block_hash, header=block.header, signature=block.signature
    )


def update_hashes_and_signature(block, private_key):
    """Updates in-place block.header.body_hash, block.block_hash and block.signature."""
    block.header.body_hash = blake2b_hash(block.body.SerializeToString())
    block_hash = blake2b_hash(block.header.SerializeToString())
    block.block_hash = block_hash

    block.signature.sig_algorithm = "ed25519"
    block.signature.sig = ed25519.SigningKey(private_key).sign(block_hash)
    return block
