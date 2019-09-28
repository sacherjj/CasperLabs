#!/usr/bin/env python3
import grpc
from concurrent import futures
from threading import Thread
import logging
import re

from . import casperlabs_client, casper_pb2_grpc, gossiping_pb2_grpc


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


def logging_pre_callback(name, request):
    logging.info(f"PROXY PRE CALLBACK: {name} {request}")
    return request


def logging_post_callback(name, request, response):
    logging.info(f"PROXY POST CALLBACK: {name} {request} {response}")
    return response


def logging_post_callback_stream(name, request, response):
    logging.info(f"PROXY POST CALLBACK STREAM: {name} {request} {response}")
    yield from response


class ProxyServicer:
    def __init__(
        self,
        node_host: str = None,
        node_port: int = None,
        proxy_port: int = None,  # just for better logging
        certificate_file: str = None,
        key_file: str = None,
        node_id: str = None,
        service_stub=None,
        pre_callback=logging_pre_callback,
        post_callback=logging_post_callback,
        post_callback_stream=logging_post_callback_stream,
    ):
        self.node_host = node_host
        self.node_port = node_port
        self.proxy_port = proxy_port
        self.certificate_file = certificate_file
        self.key_file = key_file
        self.node_id = node_id
        self.service_stub = service_stub
        self.pre_callback = pre_callback
        self.post_callback = post_callback
        self.post_callback_stream = post_callback_stream

        self.node_address = f"{self.node_host}:{self.node_port}"

        self.credentials = grpc.ssl_channel_credentials(
            root_certificates=read_binary(self.certificate_file),
            private_key=read_binary(self.key_file),
            certificate_chain=read_binary(self.certificate_file),
        )
        self.secure_channel_options = self.node_id and [
            ("grpc.ssl_target_name_override", self.node_id),
            ("grpc.default_authority", self.node_id),
        ]

    def secure_channel(self):
        channel = grpc.secure_channel(
            self.node_address, self.credentials, options=self.secure_channel_options
        )
        return channel

    def is_unary_stream(self, method_name):
        return method_name.startswith("Stream") or method_name in ("GetBlockChunked",)

    def __getattr__(self, name):

        prefix = (
            f"PROXY"
            f" {self.node_host}:{self.node_port} on {self.proxy_port}"
            f" {stub_name(self.service_stub)}::{name}"
        )
        callback_info = (
            f" [{fun_name(self.pre_callback)}"
            f" {fun_name(self.post_callback)}"
            f" {fun_name(self.post_callback_stream)}]"
        )
        logging.info(f"{prefix} {callback_info}")

        def unary_unary(request, context):
            logging.info(f"{prefix}: ({request})")
            with self.secure_channel() as channel:
                preprocessed_request = self.pre_callback(name, request)
                service_method = getattr(self.service_stub(channel), name)
                response = service_method(preprocessed_request)
                return self.post_callback(name, preprocessed_request, response)

        def unary_stream(request, context):
            logging.info(f"{prefix}: ({request})")
            with self.secure_channel() as channel:
                preprocessed_request = self.pre_callback(name, request)
                streaming_service_method = getattr(self.service_stub(channel), name)
                response_stream = streaming_service_method(preprocessed_request)
                yield from self.post_callback_stream(
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
        server_certificate_file: str = None,
        server_key_file: str = None,
        client_certificate_file: str = None,
        client_key_file: str = None,
        pre_callback=None,
        post_callback=None,
        post_callback_stream=None,
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
        self.node_id = casperlabs_client.extract_common_name(
            self.client_certificate_file
        )
        self.pre_callback = pre_callback
        self.post_callback = post_callback
        self.post_callback_stream = post_callback_stream

    def run(self):
        self.server = grpc.server(futures.ThreadPoolExecutor(max_workers=1))
        servicer = ProxyServicer(
            node_host=self.node_host,
            node_port=self.node_port,
            proxy_port=self.proxy_port,
            certificate_file=self.client_certificate_file,
            key_file=self.client_key_file,
            node_id=self.node_id,
            service_stub=self.service_stub,
            pre_callback=self.pre_callback,
            post_callback=self.post_callback,
            post_callback_stream=self.post_callback_stream,
        )
        self.add_servicer_to_server(servicer, self.server)
        self.server.add_secure_port(
            f"[::]:{self.proxy_port}",
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
    pre_callback=logging_pre_callback,
    post_callback=logging_post_callback,
    post_callback_stream=logging_post_callback_stream,
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
        pre_callback=pre_callback,
        post_callback=post_callback,
        post_callback_stream=post_callback_stream,
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
    pre_callback=logging_pre_callback,
    post_callback=logging_post_callback,
    post_callback_stream=logging_post_callback_stream,
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
        pre_callback=pre_callback,
        post_callback=post_callback,
        post_callback_stream=post_callback_stream,
    )
    t.start()
    return t
