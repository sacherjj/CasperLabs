#!/usr/bin/env python3
import grpc
from concurrent import futures
from threading import Thread
import logging
import re

from . import casperlabs_client, casper_pb2_grpc, gossiping_pb2_grpc, kademlia_pb2_grpc
from casperlabs_client import hexify


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
    def __str__(self):
        return "logging_interceptor"

    def pre_request(self, name, request):
        logging.info(f"PRE REQUEST: {name}({hexify(request)})")
        return request

    def post_request(self, name, request, response):
        logging.info(f"POST REQUEST: {name}({hexify(request)}) => ({hexify(response)})")
        return response

    def post_request_stream(self, name, request, response):
        logging.info(f"POST REQUEST STREAM: {name}({hexify(request)}) => {response}")
        yield from response


logging_interceptor = Interceptor()


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
        interceptor: Interceptor = Interceptor(),
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
            self.credentials = grpc.ssl_channel_credentials(
                root_certificates=read_binary(self.certificate_file),
                private_key=read_binary(self.key_file),
                certificate_chain=read_binary(self.certificate_file),
            )
            self.secure_channel_options = self.node_id and [
                ("grpc.ssl_target_name_override", self.node_id),
                ("grpc.default_authority", self.node_id),
            ]
        self.log_prefix = (
            f"PROXY"
            f" {self.node_host}:{self.node_port} on {self.proxy_port}"
            f" {stub_name(self.service_stub)}"
        )
        logging.info(f"{self.log_prefix}, interceptor: {self.interceptor}")

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
        def unary_unary(request, context):
            logging.info(f"{self.log_prefix}: ({hexify(request)})")
            with self.channel() as channel:
                preprocessed_request = self.interceptor.pre_request(name, request)
                service_method = getattr(self.service_stub(channel), name)
                response = service_method(preprocessed_request)
                return self.interceptor.post_request(
                    name, preprocessed_request, response
                )

        def unary_stream(request, context):
            logging.info(f"{self.log_prefix}: ({hexify(request)})")
            with self.channel() as channel:
                preprocessed_request = self.interceptor.pre_request(name, request)
                streaming_service_method = getattr(self.service_stub(channel), name)
                response_stream = streaming_service_method(preprocessed_request)
                yield from self.interceptor.post_request_stream(
                    name, preprocessed_request, response_stream
                )

        return unary_stream if self.is_unary_stream(name) else unary_unary


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
        interceptor: Interceptor = None,
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
        self.interceptor = interceptor
        self.node_id = None
        if self.encrypted_connection:
            self.node_id = casperlabs_client.extract_common_name(
                self.client_certificate_file
            )
        self.servicer = ProxyServicer(
            node_host=self.node_host,
            node_port=self.node_port,
            proxy_port=self.proxy_port,
            encrypted_connection=self.encrypted_connection,
            certificate_file=self.client_certificate_file,
            key_file=self.client_key_file,
            node_id=self.node_id,
            service_stub=self.service_stub,
            interceptor=self.interceptor,
        )

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
            f"STARTING PROXY: node_port={self.node_port} proxy_port={self.proxy_port}"
        )
        self.server.start()

    def stop(self):
        logging.info(
            f"STOPPING PROXY: node_port={self.node_port} proxy_port={self.proxy_port}"
        )
        self.server.stop(0)


def proxy_client(
    node_port=40401,
    node_host="casperlabs",
    proxy_port=50401,
    server_certificate_file: str = None,
    server_key_file: str = None,
    client_certificate_file: str = None,
    client_key_file: str = None,
    interceptor: Interceptor = logging_interceptor,
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
        interceptor=interceptor,
    )
    t.start()
    return t


def proxy_server(
    node_port=50400,
    node_host="localhost",
    proxy_port=40400,
    server_certificate_file=None,
    server_key_file=None,
    client_certificate_file=None,
    client_key_file=None,
    interceptor: Interceptor = logging_interceptor,
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
        interceptor=interceptor,
    )
    t.start()
    return t


def proxy_kademlia(
    node_port=50404,
    node_host="127.0.0.1",
    proxy_port=40404,
    interceptor: Interceptor = logging_interceptor,
):
    t = ProxyThread(
        kademlia_pb2_grpc.KademliaServiceStub,
        kademlia_pb2_grpc.add_KademliaServiceServicer_to_server,
        proxy_port=proxy_port,
        node_host=node_host,
        node_port=node_port,
        encrypted_connection=False,
        interceptor=interceptor,
    )
    t.start()
    return t
