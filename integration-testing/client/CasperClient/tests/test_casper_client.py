#!/usr/bin/env python3
"""
Python CasperLabs client unit tests suite.

The tests are very rudimentary currently. They only check that
correct objects are sent to the server. 
"""

import os
from concurrent import futures
import pytest

from casper_client import (CasperClient, CasperMessage_pb2_grpc, CasperMessage_pb2, casper_pb2_grpc, empty_pb2)
import mock_server
import grpc

RESOURCES_PATH = "../../../integration-testing/resources/"

def resource(file_name):
    return os.path.join(RESOURCES_PATH, file_name)

CONTRACT = resource("test_helloname.wasm")
PAYMENT = resource("payment.wasm")
SESSION = resource("session.wasm")

HASH = 'd9d087fe5d22dbfa1bacb57d6da8d509f7191a216cee6a971de32463ff0f284f'

client = CasperClient(port = mock_server.CL_GRPC_PORT_EXTERNAL)


@pytest.fixture()
def casper_service(request):
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=1))
    casper_pb2_grpc.add_CasperServiceServicer_to_server(mock_server.CasperServiceServicer(), server)
    port = '[::]:' + str(mock_server.CL_GRPC_PORT_EXTERNAL)
    server.add_insecure_port(port)
    server.start()
    request.addfinalizer(lambda: server.stop(0))


@pytest.fixture()
def mock_server_setup(request):
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=1))
    CasperMessage_pb2_grpc.add_DeployServiceServicer_to_server(mock_server.DeployServicer(), server)
    port = '[::]:' + str(mock_server.CL_GRPC_PORT_EXTERNAL)
    server.add_insecure_port(port)
    server.start()
    request.addfinalizer(lambda: server.stop(0))


def test_deploy(casper_service):
    response, deploy_hash = client.deploy(from_addr = 32 * b"0",
                                          gas_limit = 100000000,
                                          gas_price = 1,
                                          session = SESSION,
                                          payment = PAYMENT)
    assert type(response) == empty_pb2.Empty
    assert len(deploy_hash) == 32


def test_showBlocks(mock_server_setup):
    response = client.showBlocks(depth=10)
    i = 0
    for block in response:
        assert len(block.blockHash) == len(HASH)
        i += 1
    assert i > 0


def test_showBlock(mock_server_setup):
    response = client.showBlock(HASH)
    if response.status == 'Success':
        assert len(response.blockInfo.blockHash) == len(HASH)


def test_propose(mock_server_setup):
    response = client.propose()
    assert response


def test_visualizeDag(mock_server_setup):
    response = client.visualizeDag(depth=10)
    assert len(response.content) > 0


def test_queryState(mock_server_setup):
    try:
        response = client.queryState(blockHash=HASH, key=HASH, path="file.xxx", keyType="hash")
    except Exception as e:
        # See NODE-451.
        pass


if __name__ == '__main__':
    pytest.main(['.'])
