#!/usr/bin/env python3

import time
import grpc
from concurrent import futures

from .proto import CasperMessage_pb2_grpc

from . import grpc_proxy

def printer(name, request, v):
    print('------------------')
    print(name, ':', request, '=>', v)


def delay(name, request):
    print ('received: ', name, ':', request)
    time.sleep(1)
    return request


def serve(proxy_port: int, node_host: str, node_port: int):
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=1))
    servicer = grpc_proxy.ProxyServicer(node_host, node_port,
                                        service_stub = CasperMessage_pb2_grpc.DeployServiceStub,
                                        unary_stream_methods = ['showBlocks', 'showMainChain'],
                                        pre_callback = delay,
                                        post_callback = printer)
    CasperMessage_pb2_grpc.add_DeployServiceServicer_to_server(servicer, server)
    server.add_insecure_port(f'[::]:{proxy_port}')
    server.start()
    try:
        while True:
            time.sleep(60*60)
    except KeyboardInterrupt:
        server.stop(0)


if __name__ == '__main__':
    serve(50401, '127.0.0.1', 40401)

