# import asyncio

from grpclib.client import Channel

from . import casper_pb2 as casper
from . import casper_grpc
from . import info_pb2 as info

from casperlabs_client.utils import key_variant

DEFAULT_HOST = "localhost"
DEFAULT_PORT = 40401


class CasperLabsClientAIO:
    """
    gRPC asyncio CasperLabs client.
    """

    def __init__(self, host: str = DEFAULT_HOST, port: int = DEFAULT_PORT):
        self.host = host
        self.port = port

    async def show_blocks(self, depth=1, max_rank=0, full_view=True):
        view = full_view and info.BlockInfo.View.FULL or info.BlockInfo.View.BASIC
        channel = Channel(self.host, self.port)
        service = casper_grpc.CasperServiceStub(channel)
        result = await service.StreamBlockInfos(
            casper.StreamBlockInfosRequest(depth=depth, max_rank=max_rank, view=view)
        )
        channel.close()
        return result

    async def query_state(self, block_hash: str, key: str, path: str, key_type: str):
        q = casper.StateQuery(key_variant=key_variant(key_type), key_base16=key)
        q.path_segments.extend([name for name in path.split("/") if name])

        channel = Channel(self.host, self.port)
        reply = await casper_grpc.CasperServiceStub(channel).GetBlockState(
            casper.GetBlockStateRequest(block_hash_base16=block_hash, query=q)
        )
        channel.close()
        return reply
