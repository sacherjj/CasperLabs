import asyncio

from grpclib.client import Channel

from . import casper_pb2 as casper
from . import casper_grpc
from . import info_pb2 as info

from casperlabs_client.utils import (
    key_variant,
    make_deploy,
    sign_deploy,
    get_public_key,
)

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

    async def deploy(
        self,
        from_addr: bytes = None,
        gas_price: int = 10,
        payment: str = None,
        session: str = None,
        public_key: str = None,
        private_key: str = None,
        session_args: bytes = None,
        payment_args: bytes = None,
        payment_amount: int = None,
        payment_hash: bytes = None,
        payment_name: str = None,
        payment_uref: bytes = None,
        session_hash: bytes = None,
        session_name: str = None,
        session_uref: bytes = None,
        ttl_millis: int = 0,
        dependencies=None,
        chain_name: str = None,
    ):
        deploy = make_deploy(
            from_addr=from_addr,
            gas_price=gas_price,
            payment=payment,
            session=session,
            public_key=public_key,
            session_args=session_args,
            payment_args=payment_args,
            payment_amount=payment_amount,
            payment_hash=payment_hash,
            payment_name=payment_name,
            payment_uref=payment_uref,
            session_hash=session_hash,
            session_name=session_name,
            session_uref=session_uref,
            ttl_millis=ttl_millis,
            dependencies=dependencies,
            chain_name=chain_name,
        )

        deploy = sign_deploy(
            deploy, get_public_key(public_key, from_addr, private_key), private_key
        )
        await self.send_deploy(deploy)
        return deploy.deploy_hash.hex()

    async def send_deploy(self, deploy):
        channel = Channel(self.host, self.port)
        service = casper_grpc.CasperServiceStub(channel)
        await service.Deploy(casper.DeployRequest(deploy=deploy))
        channel.close()

    async def wait_for_deploy_processed(self, deploy_hash, on_error_raise=True):
        result = None
        while True:
            result = await self.show_deploy(deploy_hash)
            if result.status.state != 1:  # PENDING
                break
            # result.status.state == PROCESSED (2)
            await asyncio.sleep(0.1)

        if on_error_raise:
            last_processing_result = result.processing_results[0]
            if last_processing_result.is_error:
                raise Exception(
                    f"Deploy {deploy_hash} execution error: {last_processing_result.error_message}"
                )
        return result

    async def show_deploy(self, deploy_hash_base16: str, full_view=True):
        view = full_view and info.DeployInfo.View.FULL or info.DeployInfo.View.BASIC
        channel = Channel(self.host, self.port)
        service = casper_grpc.CasperServiceStub(channel)
        request = casper.GetDeployInfoRequest(
            deploy_hash_base16=deploy_hash_base16, view=view
        )
        response = await service.GetDeployInfo(request)
        channel.close()
        return response

    async def query_state(self, block_hash: str, key: str, path: str, key_type: str):
        q = casper.StateQuery(key_variant=key_variant(key_type), key_base16=key)
        q.path_segments.extend([name for name in path.split("/") if name])

        channel = Channel(self.host, self.port)
        service = casper_grpc.CasperServiceStub(channel)
        reply = await service.GetBlockState(
            casper.GetBlockStateRequest(block_hash_base16=block_hash, query=q)
        )
        channel.close()
        return reply
