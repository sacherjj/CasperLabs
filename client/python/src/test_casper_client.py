#!/usr/bin/env python3
"""
Python CasperLabs client unit test suite.

The tests are very rudimentary currently. They only check that
correct objects are sent to the server. 
"""

import os
from concurrent import futures
from casper_client import CasperClient
import grpc
import CasperMessage_pb2
import CasperMessage_pb2_grpc
import mock_server
import pytest

RESOURCES_PATH = "../../../integration-testing/resources/"

CONTRACT = os.path.join(RESOURCES_PATH, "helloname.wasm")
PAYMENT = os.path.join(RESOURCES_PATH, "payment.wasm")
SESSION = os.path.join(RESOURCES_PATH, "session.wasm")

HASH = 'd9d087fe5d22dbfa1bacb57d6da8d509f7191a216cee6a971de32463ff0f284f'

client = CasperClient()
client.port = mock_server.CL_GRPC_PORT_EXTERNAL

@pytest.fixture()
def mock_server_setup(request):
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=1))
    CasperMessage_pb2_grpc.add_DeployServiceServicer_to_server(mock_server.DeployServicer(), server)
    port = '[::]:' + str(mock_server.CL_GRPC_PORT_EXTERNAL)
    server.add_insecure_port(port)
    server.start()
    request.addfinalizer(lambda: server.stop(0))


def test_deploy(mock_server_setup):
    response = client.deploy(from_=b"00000000000000000000",
                             gas_limit=100000000,
                             gas_price=1,
                             session=SESSION,
                             payment=PAYMENT)
    assert type(response) == CasperMessage_pb2.DeployServiceResponse
    assert type(response.success) == bool
    assert type(response.message) == str


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

def test_showMainChain(mock_server_setup):
    response = client.showMainChain(depth=10)
    for block in response:
        assert len(block.blockHash) > 0


def test_findBlockWithDeploy(mock_server_setup):
    response = client.findBlockWithDeploy(b"0000000", 0)
    assert len(response.status) > 0


if __name__ == '__main__':
    pytest.main(['.'])
