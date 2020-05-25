#!/usr/bin/env python3

from pathlib import Path
import time
import functools
import warnings

from .consts import (
    SUPPORTED_KEY_ALGORITHMS,
    DEFAULT_HOST,
    DEFAULT_PORT,
    DEFAULT_INTERNAL_PORT,
    VISUALIZE_DAG_STREAM_DELAY,
    BUNDLED_TRANSFER_WASM,
)
from .insecure_grpc_service import InsecureGRPCService
from .secure_grpc_service import SecureGRPCService
from .vdag import call_dot

# Hack to fix the relative imports problems with grpc #
import sys

sys.path.append(str(Path(__file__).resolve().parents[1]))
# end of hack #

from grpc._channel import _Rendezvous

# Monkey patching of google.protobuf.text_encoding.CEscape
# to get keys and signatures in hex when printed
import google.protobuf.text_format

CEscape = google.protobuf.text_format.text_encoding.CEscape


def _hex(text, as_utf8):
    try:
        if len(text) in (32, 64, 20):
            return text.hex()
        else:
            CEscape(text, as_utf8)
    except TypeError:
        return CEscape(text, as_utf8)


google.protobuf.text_format.text_encoding.CEscape = _hex

# ~/CasperLabs/protobuf/io/casperlabs/node/api/control.proto
from . import control_pb2_grpc, consts, crypto
from . import control_pb2 as control

# ~/CasperLabs/protobuf/io/casperlabs/node/api/casper.proto
from . import casper_pb2 as casper
from . import casper_pb2_grpc

# ~/CasperLabs/protobuf/io/casperlabs/casper/consensus/info.proto
from . import info_pb2 as info

# ~/CasperLabs/protobuf/io/casperlabs/node/api/diagnostics.proto
from . import diagnostics_pb2_grpc
from . import empty_pb2

from . import vdag
from . import abi

from casperlabs_client.contract import bundled_contract
from casperlabs_client.query_state import key_variant
from casperlabs_client.deploy import make_deploy, sign_deploy, _select_public_key_to_use


class InternalError(Exception):
    """
    The only exception that API calls can throw.
    Internal errors like gRPC exceptions will be caught
    and this exception thrown instead, so the user does
    not have to worry about handling any other exceptions.
    """

    # TODO: Is there a reason we need to hide error types?
    def __init__(self, status="", details=""):
        super(InternalError, self).__init__()
        self.status = status
        self.details = details

    def __str__(self):
        return f"{self.status}: {self.details}"


def api(function):
    """
    Decorator of API functions that protects user code from
    unknown exceptions raised by gRPC or internal API errors.
    It will catch all exceptions and throw InternalError.

    :param function: function to be decorated
    :return: decorated function
    """

    @functools.wraps(function)
    def wrapper(*args, **kwargs):
        try:
            return function(*args, **kwargs)
        except (SyntaxError, TypeError, InternalError):
            raise
        except _Rendezvous as e:
            raise InternalError(str(e.code()), e.details())
        except Exception as e:
            raise InternalError(details=str(e)) from e

    return wrapper


class CasperLabsClient:
    """
    gRPC CasperLabs client.
    """

    def __init__(
        self,
        host: str = DEFAULT_HOST,
        port: int = DEFAULT_PORT,
        port_internal: int = DEFAULT_INTERNAL_PORT,
        node_id: str = None,
        certificate_file: str = None,
    ):
        """
        CasperLabs client's constructor.

        :param host:                Hostname or IP of node on which gRPC service is running
        :param port:                Port used for external gRPC API
        :param certificate_file:    Certificate file for TLS
        :param node_id:             node_id of the node, for gRPC encryption
        """
        self.host = host
        self.port = port
        self.node_id = node_id
        self.certificate_file = certificate_file

        if node_id:
            self.casper_service = SecureGRPCService(
                host, port, casper_pb2_grpc.CasperServiceStub, node_id, certificate_file
            )
            self.control_service = SecureGRPCService(
                # We currently assume that if node_id is given then
                # we get certificate_file too. This is unlike in the Scala client
                # where node_id is all that's needed for configuring secure connection.
                # The reason for this is that currently it doesn't seem to be possible
                # to open a secure grpc connection in Python without supplying any
                # certificate on the client side.
                host,
                port_internal,
                control_pb2_grpc.ControlServiceStub,
                node_id,
                certificate_file,
            )
            self.diagnostics_service = SecureGRPCService(
                host,
                port,
                diagnostics_pb2_grpc.DiagnosticsStub,
                node_id,
                certificate_file,
            )

        else:
            self.casper_service = InsecureGRPCService(
                host, port, casper_pb2_grpc.CasperServiceStub
            )
            self.control_service = InsecureGRPCService(
                host, port_internal, control_pb2_grpc.ControlServiceStub
            )
            self.diagnostics_service = InsecureGRPCService(
                host, port, diagnostics_pb2_grpc.DiagnosticsStub
            )

    @api
    def make_deploy(
        self,
        from_addr: bytes = None,
        gas_price: int = 10,
        payment: str = None,
        session: str = None,
        public_key: str = None,
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
        dependencies: list = None,
        chain_name: str = None,
    ):
        """
        Create a protobuf deploy object. See deploy for description of parameters.
        """
        return make_deploy(
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

    @api
    def sign_deploy(self, deploy, public_key, private_key_file):
        return sign_deploy(deploy, public_key, private_key_file)

    @api
    def deploy(
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
    ) -> str:
        """
        Deploy a smart contract source file to Casper on an existing running node.
        The deploy will be packaged and sent as a block to the network depending
        on the configuration of the Casper instance.

        :param from_addr:      Purse address that will be used to pay for the deployment.
        :param gas_price:      The price of gas for this transaction in units dust/gas.
                               Must be positive integer.
        :param payment:        Path to the file with payment code.
        :param session:        Path to the file with session code.
        :param public_key:     Path to a file with public key (Ed25519)
        :param private_key:    Path to a file with private key (Ed25519)
        :param session_args:   List of ABI encoded arguments of session contract
        :param payment_args:   List of ABI encoded arguments of payment contract
        :param payment_amount: Amount to be used with standard payment
        :param session_hash:   Hash of the stored contract to be called in the
                               session; base16 encoded.
        :param session_name:   Name of the stored contract (associated with the
                               executing account) to be called in the session.
        :param session_uref:   URef of the stored contract to be called in the
                               session; base16 encoded.
        :param payment_hash:   Hash of the stored contract to be called in the
                               payment; base16 encoded.
        :param payment_name:   Name of the stored contract (associated with the
                               executing account) to be called in the payment.
        :param payment_uref:   URef of the stored contract to be called in the
                               payment; base16 encoded.
        :param ttl_millis:     Time to live. Time (in milliseconds) that the
                               deploy will remain valid for.
        :param dependencies:   List of deploy hashes (base16 encoded) which
                               must be executed before this deploy.
        :param chain_name:     Name of the chain to optionally restrict the
                               deploy from being accidentally included anywhere else.
        :return:               deploy hash in base16 format
        """

        deploy = self.make_deploy(
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

        deploy = self.sign_deploy(
            deploy,
            _select_public_key_to_use(public_key, from_addr, private_key),
            private_key,
        )
        self.send_deploy(deploy)
        return deploy.deploy_hash.hex()

    @api
    def transfer(self, target_account_hex, amount, **deploy_args):
        target_account_bytes = bytes.fromhex(target_account_hex)
        deploy_args["session"] = bundled_contract(BUNDLED_TRANSFER_WASM)
        deploy_args["session_args"] = abi.ABI.args(
            [
                abi.ABI.account("account", target_account_bytes),
                abi.ABI.u512("amount", amount),
            ]
        )
        return self.deploy(**deploy_args)

    @api
    def send_deploy(self, deploy):
        self.casper_service.Deploy(casper.DeployRequest(deploy=deploy))

    @api
    def show_blocks(self, depth: int = 1, max_rank=0, full_view=True):
        """
        Get slices of the DAG, going backwards, rank by rank.

        :param depth:     How many of the top ranks of the DAG to show.
        :param max_rank:  Maximum rank to go back from.
                          0 means go from the current tip of the DAG.
        :param full_view: Full view if True, otherwise basic.
        :return:          Generator of block info objects.
        """
        yield from self.casper_service.StreamBlockInfos_stream(
            casper.StreamBlockInfosRequest(
                depth=depth,
                max_rank=max_rank,
                view=(
                    full_view and info.BlockInfo.View.FULL or info.BlockInfo.View.BASIC
                ),
            )
        )

    @api
    def showBlocks(self, depth: int = 1, max_rank=0, full_view=True):
        """
        DEPRECATED: Call `show_blocks`

        Get slices of the DAG, going backwards, rank by rank.

        :param depth:     How many of the top ranks of the DAG to show.
        :param max_rank:  Maximum rank to go back from.
                          0 means go from the current tip of the DAG.
        :param full_view: Full view if True, otherwise basic.
        :return:          Generator of block info objects.
        """
        warnings.warn(
            "showBlocks is deprecated and replaced with show_blocks.",
            DeprecationWarning,
        )
        for block_info_request in self.show_blocks(depth, max_rank, full_view):
            yield block_info_request

    @api
    def show_block(self, block_hash_base16: str, full_view=True):
        """
                Returns object describing a block known by Casper on an existing running node.

                :param block_hash_base16: hash of the block to be retrieved
                :param full_view:         full view if True, otherwise basic
                :return:                  object representing the retrieved block
                """
        return self.casper_service.GetBlockInfo(
            casper.GetBlockInfoRequest(
                block_hash_base16=block_hash_base16,
                view=(
                    full_view and info.BlockInfo.View.FULL or info.BlockInfo.View.BASIC
                ),
            )
        )

    @api
    def showBlock(self, block_hash_base16: str, full_view=True):
        """
        DEPRECATED: call `show_block`

        Returns object describing a block known by Casper on an existing running node.

        :param block_hash_base16: hash of the block to be retrieved
        :param full_view:         full view if True, otherwise basic
        :return:                  object representing the retrieved block
        """
        warnings.warn(
            "showBlock is deprecated and replaced with show_block.", DeprecationWarning
        )
        return self.show_block(block_hash_base16, full_view)

    @api
    def propose(self):
        """"
        THIS METHOD IS DEPRECATED! It will be removed soon.

        Propose a block using deploys in the pool.

        :return:    response object with block_hash
        """
        warnings.warn("propose is deprecated and will be removed.", DeprecationWarning)
        return self.control_service.Propose(control.ProposeRequest())

    @api
    def visualize_dag(
        self,
        depth: int,
        out: str = None,
        show_justification_lines: bool = False,
        stream: str = None,
        delay_in_seconds=VISUALIZE_DAG_STREAM_DELAY,
    ):
        """
        Generate DAG in DOT format.

        :param depth:                     depth in terms of block height
        :param out:                       output image filename, outputs to stdout if
                                          not specified, must end with one of the png,
                                          svg, svg_standalone, xdot, plain, plain_ext,
                                          ps, ps2, json, json0
        :param show_justification_lines:  if justification lines should be shown
        :param stream:                    subscribe to changes, 'out' has to specified,
                                          valid values are 'single-output', 'multiple-outputs'
        :param delay_in_seconds:          delay in seconds when polling for updates (streaming)
        :return:                          Yields generated DOT source or file name when out provided.
                                          Generates endless stream of file names if stream is not None.
        """
        block_infos = list(self.show_blocks(depth, full_view=False))
        dot_dag_description = vdag.generate_dot(block_infos, show_justification_lines)
        if not out:
            yield dot_dag_description
            return

        parts = out.split(".")
        file_format = parts[-1]
        file_name_base = ".".join(parts[:-1])

        iteration = -1

        def file_name():
            nonlocal iteration, file_format
            if not stream or stream == "single-output":
                return out
            else:
                iteration += 1
                return f"{file_name_base}_{iteration}.{file_format}"

        yield call_dot(dot_dag_description, file_name(), file_format)
        previous_block_hashes = set(b.summary.block_hash for b in block_infos)
        while stream:
            time.sleep(delay_in_seconds)
            block_infos = list(self.show_blocks(depth, full_view=False))
            block_hashes = set(b.summary.block_hash for b in block_infos)
            if block_hashes != previous_block_hashes:
                dot_dag_description = vdag.generate_dot(
                    block_infos, show_justification_lines
                )
                yield call_dot(dot_dag_description, file_name(), file_format)
                previous_block_hashes = block_hashes

    @api
    def visualizeDag(
        self,
        depth: int,
        out: str = None,
        show_justification_lines: bool = False,
        stream: str = None,
        delay_in_seconds=5,
    ):
        """
        DEPRECATED: call `visualize_dag`

        Generate DAG in DOT format.

        :param depth:                     depth in terms of block height
        :param out:                       output image filename, outputs to stdout if
                                          not specified, must end with one of the png,
                                          svg, svg_standalone, xdot, plain, plain_ext,
                                          ps, ps2, json, json0
        :param show_justification_lines:  if justification lines should be shown
        :param stream:                    subscribe to changes, 'out' has to specified,
                                          valid values are 'single-output', 'multiple-outputs'
        :param delay_in_seconds:          delay in seconds when polling for updates (streaming)
        :return:                          Yields generated DOT source or file name when out provided.
                                          Generates endless stream of file names if stream is not None.
        """
        warnings.warn(
            "visualizeDag is deprecated and replaced with visualize_dag.",
            DeprecationWarning,
        )
        for output in self.visualize_dag(
            depth, out, show_justification_lines, stream, delay_in_seconds
        ):
            yield output

    @api
    def query_state(self, block_hash: str, key: str, path: str, key_type: str):
        """
        Query a value in the global state.

        :param block_hash:        Hash of the block to query the state of
        :param key:               Base16 encoding of the base key
        :param path:              Path to the value to query. Must be of the form
                                  'key1/key2/.../keyn'
        :param key_type:          Type of base key. Must be one of 'hash', 'uref', 'address' or 'local'.
                                  For 'local' key type, 'key' value format is {seed}:{rest},
                                  where both parts are hex encoded."
        :return:                  QueryStateResponse object
        """
        q = casper.StateQuery(key_variant=key_variant(key_type), key_base16=key)
        q.path_segments.extend([name for name in path.split("/") if name])
        return self.casper_service.GetBlockState(
            casper.GetBlockStateRequest(block_hash_base16=block_hash, query=q)
        )

    @api
    def queryState(self, blockHash: str, key: str, path: str, keyType: str):
        """
        DEPRECATED: call `query_state`

        Query a value in the global state.

        :param blockHash:         Hash of the block to query the state of
        :param key:               Base16 encoding of the base key
        :param path:              Path to the value to query. Must be of the form
                                  'key1/key2/.../keyn'
        :param keyType:           Type of base key. Must be one of 'hash', 'uref', 'address' or 'local'.
                                  For 'local' key type, 'key' value format is {seed}:{rest},
                                  where both parts are hex encoded."
        :return:                  QueryStateResponse object
        """
        warnings.warn(
            "queryState is deprecated and replaced with query_state.",
            DeprecationWarning,
        )
        return self.query_state(blockHash, key, path, keyType)

    @api
    def balance(self, address: str, block_hash: str):
        """ Return balance of the main purse of an account

        :param address:    Public key address of account.
        :param block_hash: Hash of block from which to return balance.
        """
        value = self.query_state(block_hash, address, "", "address")
        try:
            account = value.account
        except AttributeError:
            return InternalError(
                "balance", f"Expected Account type value under {address}."
            )

        urefs = [u for u in account.named_keys if u.name == "mint"]
        if len(urefs) == 0:
            raise InternalError(
                "balance",
                "Account's named_keys map did not contain Mint contract address.",
            )

        mint_public = urefs[0]

        mint_public_hex = mint_public.key.uref.uref.hex()
        purse_addr_hex = account.main_purse.uref.hex()
        local_key_value = f"{mint_public_hex}:{purse_addr_hex}"

        balance_u_ref = self.query_state(block_hash, local_key_value, "", "local")
        balance_u_ref_hex = balance_u_ref.cl_value.value.key.uref.uref.hex()
        balance = self.query_state(block_hash, balance_u_ref_hex, "", "uref")
        balance_str_value = balance.cl_value.value.u512.value
        return int(balance_str_value)

    @api
    def show_deploy(
        self,
        deploy_hash: str,
        full_view: bool = False,
        wait_for_processed: bool = False,
        delay: int = consts.STATUS_CHECK_DELAY,
        timeout_seconds: int = consts.STATUS_TIMEOUT,
    ):
        """
        Retrieve information about a single deploy by hash.

        :param deploy_hash:         Hash of deploy in base16 (hex)
        :param full_view:           Show full view of deploy, default false
        :param wait_for_processed:  Block return until deploy is not pending, default false
        :param delay:               Delay between status checks while waiting
        :timeout_seconds:           Time to return of wait even if deploy is pending
        """
        start_time = time.time()
        while True:
            deploy_info = self.casper_service.GetDeployInfo(
                casper.GetDeployInfoRequest(
                    deploy_hash_base16=deploy_hash, view=self._deploy_view(full_view)
                )
            )
            if (
                wait_for_processed
                and deploy_info.status.state == info.DeployInfo.State.PENDING
            ):
                if time.time() - start_time > timeout_seconds:
                    raise Exception(
                        f"Timed out waiting for deploy {deploy_hash} to be processed"
                    )
                time.sleep(delay)
                continue
            return deploy_info

    @api
    def showDeploy(
        self,
        deploy_hash_base16: str,
        full_view: bool = False,
        wait_for_processed: bool = False,
        delay: int = consts.STATUS_CHECK_DELAY,
        timeout_seconds: int = consts.STATUS_TIMEOUT,
    ):
        """
        DEPRECATED: call `show_deploy`

        Retrieve information about a single deploy by hash.
        """
        warnings.warn(
            "showDeploy is deprecated and replaced with show_deploy.",
            DeprecationWarning,
        )
        return self.show_deploy(
            deploy_hash_base16, full_view, wait_for_processed, delay, timeout_seconds
        )

    @api
    def show_deploys(self, block_hash_base16: str, full_view=True):
        """
        Get the processed deploys within a block.
        """
        yield from self.casper_service.StreamBlockDeploys_stream(
            casper.StreamBlockDeploysRequest(
                block_hash_base16=block_hash_base16, view=self._deploy_view(full_view)
            )
        )

    @api
    def showDeploys(self, block_hash_base16: str, full_view=True):
        """
        DEPRECATED: use `show_deploys`

        Get the processed deploys within a block.
        """
        warnings.warn(
            "showDeploys is deprecated and replaced with show_deploys.",
            DeprecationWarning,
        )
        for block_deploys in self.show_deploy(block_hash_base16, full_view):
            yield block_deploys

    @api
    def stream_events(
        self,
        all: bool = False,
        block_added: bool = False,
        block_finalized: bool = False,
        deploy_added: bool = False,
        deploy_discarded: bool = False,
        deploy_requeued: bool = False,
        deploy_processed: bool = False,
        deploy_finalized: bool = False,
        deploy_orphaned: bool = False,
        account_public_keys=None,
        deploy_hashes=None,
        min_event_id: int = 0,
        max_event_id: int = 0,
    ):
        """
        See StreamEventsRequest in
            ~/CasperLabs/protobuf/io/casperlabs/node/api/casper.proto
        for description of types of events.

        Note, you must subscribe to some events (pass True to some keywords other than account_public_keys or deploy_hashes)
        otherwise this generator will block forever.
        """
        if all:
            block_added = True
            block_finalized = True
            deploy_added = True
            deploy_discarded = True
            deploy_requeued = True
            deploy_processed = True
            deploy_finalized = True
            deploy_orphaned = True

        yield from self.casper_service.StreamEvents_stream(
            casper.StreamEventsRequest(
                block_added=block_added,
                block_finalized=block_finalized,
                deploy_added=deploy_added,
                deploy_discarded=deploy_discarded,
                deploy_requeued=deploy_requeued,
                deploy_processed=deploy_processed,
                deploy_finalized=deploy_finalized,
                deploy_orphaned=deploy_orphaned,
                deploy_filter=casper.StreamEventsRequest.DeployFilter(
                    account_public_keys=[
                        bytes.fromhex(pk) for pk in account_public_keys or []
                    ],
                    deploy_hashes=[bytes.fromhex(h) for h in deploy_hashes or []],
                ),
                min_event_id=min_event_id,
                max_event_id=max_event_id,
            )
        )

    @api
    def wait_for_deploy_processed(
        self,
        deploy_hash,
        on_error_raise=True,
        delay=consts.STATUS_CHECK_DELAY,
        timeout_seconds=consts.STATUS_TIMEOUT,
    ):
        start_time = time.time()
        result = None
        while True:
            if time.time() - start_time > timeout_seconds:
                raise Exception(
                    f"Timed out waiting for deploy {deploy_hash} to be processed"
                )

            result = self.show_deploy(deploy_hash, full_view=False)
            if result.status.state != info.DeployInfo.State.PENDING:
                break
            time.sleep(delay)

        if on_error_raise:
            if len(result.processing_results) == 0:
                raise Exception(f"Deploy {deploy_hash} status: {result.status}")

            last_processing_result = result.processing_results[0]
            if last_processing_result.is_error:
                raise Exception(
                    f"Deploy {deploy_hash} execution error: {last_processing_result.error_message}"
                )
        return result

    @api
    def show_peers(self):
        return list(self.diagnostics_service.ListPeers(empty_pb2.Empty()).peers)

    @api
    def account_hash(self, algorithm: str, public_key) -> bytes:
        """ Create account hash based on algorithm and public key """
        if algorithm not in SUPPORTED_KEY_ALGORITHMS:
            raise ValueError(f"algorithm must be one of {SUPPORTED_KEY_ALGORITHMS}.")
        return crypto.blake2b_hash(algorithm.encode("UTF-8") + b"0x00" + public_key)
