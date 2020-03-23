import asyncio

from grpclib.client import Channel
from grpclib.protocol import H2Protocol
from ssl import create_default_context, Purpose, CERT_REQUIRED
from typing import cast

from . import casper_pb2 as casper
from . import casper_grpc
from . import info_pb2 as info

from casperlabs_client.utils import (
    key_variant,
    make_deploy,
    sign_deploy,
    get_public_key,
    bundled_contract,
    extract_common_name,
)
from . import abi

DEFAULT_HOST = "localhost"
DEFAULT_PORT = 40401


class ScopedChannel(object):
    def __init__(self, channel):
        self.channel = channel

    def __enter__(self):
        return self.channel

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.channel.close()


class CasperService(object):
    def __init__(self, host, port, certificate_file, private_key_file):
        self.host = host
        self.port = port
        self.certificate_file = certificate_file
        self.private_key_file = private_key_file
        self.node_id = (
            certificate_file and extract_common_name(certificate_file) or None
        )

    def __getattr__(self, name):
        async def method(*args):
            with ScopedChannel(self.channel()) as channel:
                service = casper_grpc.CasperServiceStub(channel)
                return await getattr(service, name)(*args)

        return method

    def channel(self):
        ssl = None
        if self.certificate_file:
            ssl = create_default_context(Purpose.SERVER_AUTH)
            # ssl.load_default_certs()
            ssl.load_cert_chain(self.certificate_file, self.private_key_file)
            ssl.verify_mode = CERT_REQUIRED
            ssl.check_hostname = True
        return SecureChannel(
            self.host, self.port, ssl=ssl, server_hostname=self.node_id
        )


class SecureChannel(Channel):
    def __init__(self, host, port, ssl, server_hostname):
        self.server_hostname = server_hostname
        super().__init__(host, port, ssl=ssl)

    async def _create_connection(self) -> H2Protocol:
        if self._path is not None:
            _, protocol = await self._loop.create_unix_connection(
                self._protocol_factory, self._path, ssl=self._ssl
            )
        else:
            _, protocol = await self._loop.create_connection(
                self._protocol_factory,
                self._host,
                self._port,
                # passing server_hostname to create_connection is the reason we subclass Channel,
                # the base class doesn't do it.
                server_hostname=self.server_hostname,
                ssl=self._ssl,
            )
        return cast(H2Protocol, protocol)


class CasperLabsClientAIO(object):
    """
    gRPC asyncio CasperLabs client.
    """

    DEPLOY_STATUS_CHECK_DELAY = 0.5

    def __init__(
        self,
        host: str = DEFAULT_HOST,
        port: int = DEFAULT_PORT,
        certificate_file: str = None,
        private_key_file: str = None,
    ):
        self.casper_service = CasperService(
            host, port, certificate_file, private_key_file
        )

    async def show_blocks(self, depth=1, max_rank=0, full_view=True):
        return await self.casper_service.StreamBlockInfos(
            casper.StreamBlockInfosRequest(
                depth=depth, max_rank=max_rank, view=self._block_info_view(full_view)
            )
        )

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
        return await self.casper_service.Deploy(casper.DeployRequest(deploy=deploy))

    async def wait_for_deploy_processed(
        self, deploy_hash, on_error_raise=True, delay=DEPLOY_STATUS_CHECK_DELAY
    ):
        result = None
        while True:
            result = await self.show_deploy(deploy_hash)
            if result.status.state != info.DeployInfo.State.PENDING:
                break
            await asyncio.sleep(delay)

        if on_error_raise:
            if len(result.processing_results) == 0:
                raise Exception(f"Deploy {deploy_hash} status: {result.status}")

            last_processing_result = result.processing_results[0]
            if last_processing_result.is_error:
                raise Exception(
                    f"Deploy {deploy_hash} execution error: {last_processing_result.error_message}"
                )
        return result

    async def show_deploy(self, deploy_hash_base16: str, full_view=False):
        return await self.casper_service.GetDeployInfo(
            casper.GetDeployInfoRequest(
                deploy_hash_base16=deploy_hash_base16,
                view=self._deploy_info_view(full_view),
            )
        )

    async def query_state(self, block_hash: str, key: str, path: str, key_type: str):
        q = casper.StateQuery(key_variant=key_variant(key_type), key_base16=key)
        q.path_segments.extend([name for name in path.split("/") if name])
        return await self.casper_service.GetBlockState(
            casper.GetBlockStateRequest(block_hash_base16=block_hash, query=q)
        )

    async def transfer(self, target_account_hex, amount, **deploy_args):
        deploy_args["session"] = bundled_contract("transfer_to_account_u512.wasm")
        deploy_args["session_args"] = abi.ABI.args(
            [
                abi.ABI.account("account", bytes.fromhex(target_account_hex)),
                abi.ABI.u512("amount", amount),
            ]
        )
        return await self.deploy(**deploy_args)

    async def balance(self, address: str, block_hash: str):
        value = await self.query_state(block_hash, address, "", "address")
        account = None
        try:
            account = value.account
        except AttributeError:
            return Exception(f"balance: Expected Account type value under {address}.")

        urefs = [u for u in account.named_keys if u.name == "mint"]
        if len(urefs) == 0:
            raise Exception(
                "balance: Account's named_keys map did not contain Mint contract address."
            )

        mint_public = urefs[0]

        mint_public_hex = mint_public.key.uref.uref.hex()
        purse_addr_hex = account.main_purse.uref.hex()
        local_key_value = f"{mint_public_hex}:{purse_addr_hex}"

        balance_uref = await self.query_state(block_hash, local_key_value, "", "local")
        balance_uref_hex = balance_uref.cl_value.value.key.uref.uref.hex()
        balance = await self.query_state(block_hash, balance_uref_hex, "", "uref")
        balance_str_value = balance.cl_value.value.u512.value
        return int(balance_str_value)

    async def show_block(self, block_hash_base16: str, full_view=True):
        return await self.casper_service.GetBlockInfo(
            casper.GetBlockInfoRequest(
                block_hash_base16=block_hash_base16,
                view=self._block_info_view(full_view),
            )
        )

    async def show_deploys(self, block_hash_base16: str, full_view=False):
        return await self.casper_service.StreamBlockDeploys(
            casper.StreamBlockDeploysRequest(
                block_hash_base16=block_hash_base16,
                view=self._deploy_info_view(full_view),
            )
        )

    def _deploy_info_view(self, full_view):
        return full_view and info.DeployInfo.View.FULL or info.DeployInfo.View.BASIC

    def _block_info_view(self, full_view):
        return full_view and info.BlockInfo.View.FULL or info.BlockInfo.View.BASIC
