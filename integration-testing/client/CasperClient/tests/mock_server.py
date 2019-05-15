#!/usr/bin/env python3
"""
Mock gRPC server (node) used in Python client's unit tests suite.
"""

import string
from concurrent import futures
import time
import grpc
import pprint

import CasperMessage_pb2
import CasperMessage_pb2_grpc

CL_GRPC_PORT_EXTERNAL=3477

HASH = 'd9d087fe5d22dbfa1bacb57d6da8d509f7191a216cee6a971de32463ff0f284f'

SAMPLE_DOT = """
digraph "dag" {
  rankdir=BT
  node [width=0 height=0 margin=0.03 fontsize=8]
  splines=false
  subgraph "cluster_" {
    label = ""
    "d9d087fe5d..." [style=filled shape=box]
    "1_" [style=invis shape=box]
    "2_" [style=invis shape=box]
    "3_" [style=invis shape=box]
    "4_" [style=invis shape=box]
    "5_" [style=invis shape=box]
    "d9d087fe5d..." -> "1_" [style=invis]
    "1_" -> "2_" [style=invis]
    "2_" -> "3_" [style=invis]
    "3_" -> "4_" [style=invis]
    "4_" -> "5_" [style=invis]
  }
  subgraph "cluster_9dfcf4f851..." {
    label = "9dfcf4f851..."
    "0_9dfcf4f851..." [style=invis shape=box]
    "30baf73717..." [shape=box]
    "33c8b59ddd..." [shape=box]
    "9bae467c70..." [shape=box]
    "5736298633..." [shape=box]
    "7f12df896d..." [shape=box]
    "0_9dfcf4f851..." -> "30baf73717..." [style=invis]
    "30baf73717..." -> "33c8b59ddd..." [style=invis]
    "33c8b59ddd..." -> "9bae467c70..." [style=invis]
    "9bae467c70..." -> "5736298633..." [style=invis]
    "5736298633..." -> "7f12df896d..." [style=invis]
  }
  "7f12df896d..." -> "5736298633..." [constraint=false]
  "30baf73717..." -> "d9d087fe5d..." [constraint=false]
  "33c8b59ddd..." -> "30baf73717..." [constraint=false]
  "9bae467c70..." -> "33c8b59ddd..." [constraint=false]
  "5736298633..." -> "9bae467c70..." [constraint=false]
}

"""


class DeployServicer(CasperMessage_pb2_grpc.DeployServiceServicer):

  def DoDeploy(self, request, context):
    context.set_code(grpc.StatusCode.OK)
    context.set_details('')
    return CasperMessage_pb2.DeployServiceResponse()

  def createBlock(self, request, context):
    context.set_code(grpc.StatusCode.OK)
    return CasperMessage_pb2.DeployServiceResponse()


  def showBlock(self, request, context):
    context.set_code(grpc.StatusCode.OK)
    return CasperMessage_pb2.BlockQueryResponse()

  def visualizeDag(self, request, context):
    """Get DAG in DOT format.
    """
    context.set_code(grpc.StatusCode.OK)
    b = CasperMessage_pb2.VisualizeBlocksResponse()
    b.content = SAMPLE_DOT
    return b

  def showMainChain(self, request, context):
    context.set_code(grpc.StatusCode.OK)
    return CasperMessage_pb2.BlockInfoWithoutTuplespace()

  def showBlocks(self, request, context):
    context.set_code(grpc.StatusCode.OK)
    b = CasperMessage_pb2.BlockInfoWithoutTuplespace()
    b.blockHash = HASH
    yield b

  def findBlockWithDeploy(self, request, context):
    context.set_code(grpc.StatusCode.OK)
    b = CasperMessage_pb2.BlockQueryResponse()
    b.status = "SUCCESS"
    return b

  def queryState(self, request, context):
    context.set_code(grpc.StatusCode.OK)
    return CasperMessage_pb2.QueryStateResponse()

####

def serve():
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=1))
    CasperMessage_pb2_grpc.add_DeployServiceServicer_to_server(DeployServicer(), server)
    port = '[::]:' + str(CL_GRPC_PORT_EXTERNAL)
    server.add_insecure_port(port)
    server.start()
    try:
        while True:
            time.sleep(60*60)
    except KeyboardInterrupt:
        server.stop(0)


if __name__ == '__main__':
    serve()
