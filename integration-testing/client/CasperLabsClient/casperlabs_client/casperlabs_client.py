#!/usr/bin/env python3
"""
CasperLabs Client API library and command line tool.
"""

from pathlib import Path

# Hack to fix the relative imports problems #
import sys

sys.path.append(str(Path(__file__).resolve().parents[1]))
# end of hack #
import os
import time
import grpc
from grpc._channel import _Rendezvous
import ssl
import functools
import logging
import pkg_resources
import tempfile

# Monkey patching of google.protobuf.text_encoding.CEscape
# to get keys and signatures in hex when printed
import google.protobuf.text_format

CEscape = google.protobuf.text_format.text_encoding.CEscape


def _hex(text, as_utf8):
    try:
        return (len(text) in (32, 64, 20)) and text.hex() or CEscape(text, as_utf8)
    except TypeError:
        return CEscape(text, as_utf8)


google.protobuf.text_format.text_encoding.CEscape = _hex

# ~/CasperLabs/protobuf/io/casperlabs/node/api/control.proto
from . import control_pb2_grpc
from . import control_pb2 as control

# ~/CasperLabs/protobuf/io/casperlabs/node/api/casper.proto
from . import casper_pb2 as casper
from . import casper_pb2_grpc

# ~/CasperLabs/protobuf/io/casperlabs/casper/consensus/consensus.proto
from . import consensus_pb2 as consensus

# ~/CasperLabs/protobuf/io/casperlabs/casper/consensus/info.proto
from . import info_pb2 as info

from . import vdag
from . import abi
from . import crypto


DEFAULT_HOST = "localhost"
DEFAULT_PORT = 40401
DEFAULT_INTERNAL_PORT = 40402


class InternalError(Exception):
    """
    The only exception that API calls can throw.
    Internal errors like gRPC exceptions will be caught
    and this exception thrown instead, so the user does
    not have to worry about handling any other exceptions.
    """

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
    :return:
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


def _read_binary(file_name: str):
    with open(file_name, "rb") as f:
        return f.read()


def _encode_contract(contract_options, contract_args):
    """
    """
    file_name, hash, name, uref = contract_options
    C = consensus.Deploy.Code
    if file_name:
        return C(wasm=_read_binary(file_name), args=contract_args)
    if hash:
        return C(hash=hash, args=contract_args)
    if name:
        return C(name=name, args=contract_args)
    if uref:
        return C(uref=uref, args=contract_args)
    raise Exception("One of wasm, hash, name or uref is required")


def _serialize(o) -> bytes:
    return o.SerializeToString()


NUMBER_OF_RETRIES = 5

# Initial delay in seconds before an attempt to retry
INITIAL_DELAY = 0.3


def retry_wrapper(function, *args):
    delay = INITIAL_DELAY
    for i in range(NUMBER_OF_RETRIES):
        try:
            return function(*args)
        except _Rendezvous as e:
            if e.code() == grpc.StatusCode.UNAVAILABLE and i < NUMBER_OF_RETRIES - 1:
                delay += delay
                logging.warning(f"Retrying after {e} in {delay} seconds")
                time.sleep(delay)
            else:
                raise


def retry_unary(function):
    @functools.wraps(function)
    def wrapper(*args):
        return retry_wrapper(function, *args)

    return wrapper


def retry_stream(function):
    @functools.wraps(function)
    def wrapper(*args):
        yield from retry_wrapper(function, *args)

    return wrapper


class InsecureGRPCService:
    def __init__(self, host, port, serviceStub):
        self.address = f"{host}:{port}"
        self.serviceStub = serviceStub

    def __getattr__(self, name):

        logging.debug(
            f"Creating insecure connection to {self.address} ({self.serviceStub})"
        )

        @retry_unary
        def unary_unary(*args):
            logging.debug(
                f"Insecure {self.address} ({self.serviceStub}): {name} {list(args)}"
            )
            with grpc.insecure_channel(self.address) as channel:
                return getattr(self.serviceStub(channel), name)(*args)

        @retry_stream
        def unary_stream(*args):
            logging.debug(
                f"Insecure {self.address} ({self.serviceStub}): {name} {list(args)}"
            )
            with grpc.insecure_channel(self.address) as channel:
                yield from getattr(self.serviceStub(channel), name[: -len("_stream")])(
                    *args
                )

        return name.endswith("_stream") and unary_stream or unary_unary


def extract_common_name(certificate_file: str) -> str:
    cert_dict = ssl._ssl._test_decode_cert(certificate_file)
    return [t[0][1] for t in cert_dict["subject"] if t[0][0] == "commonName"][0]


def abi_byte_array(a: bytes) -> bytes:
    return a


class SecureGRPCService:
    def __init__(self, host, port, serviceStub, node_id, certificate_file):
        self.address = f"{host}:{port}"
        self.serviceStub = serviceStub
        self.node_id = node_id or extract_common_name(certificate_file)
        self.certificate_file = certificate_file
        with open(self.certificate_file, "rb") as f:
            self.credentials = grpc.ssl_channel_credentials(f.read())
        self.secure_channel_options = self.node_id and (
            ("grpc.ssl_target_name_override", self.node_id),
            ("grpc.default_authority", self.node_id),
        )

    def __getattr__(self, name):
        logging.debug(
            f"Creating secure connection to {self.address} ({self.serviceStub})"
        )

        @retry_unary
        def unary_unary(*args):
            with grpc.secure_channel(
                self.address, self.credentials, options=self.secure_channel_options
            ) as channel:
                return getattr(self.serviceStub(channel), name)(*args)

        @retry_stream
        def unary_stream(*args):
            with grpc.secure_channel(
                self.address, self.credentials, options=self.secure_channel_options
            ) as channel:
                yield from getattr(self.serviceStub(channel), name[: -len("_stream")])(
                    *args
                )

        return name.endswith("_stream") and unary_stream or unary_unary


class CasperLabsClient:
    """
    gRPC CasperLabs client.
    """

    # Note, there is also casper.StateQuery.KeyVariant.KEY_VARIANT_UNSPECIFIED,
    # but it doesn't seem to have an official string representation
    # ("key_variant_unspecified"? "unspecified"?) and is not used by the client.
    STATE_QUERY_KEY_VARIANT = {
        "hash": casper.StateQuery.KeyVariant.HASH,
        "uref": casper.StateQuery.KeyVariant.UREF,
        "address": casper.StateQuery.KeyVariant.ADDRESS,
        "local": casper.StateQuery.KeyVariant.LOCAL,
    }

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

        :param host:            Hostname or IP of node on which gRPC service is running
        :param port:            Port used for external gRPC API
        :param port_internal:   Port used for internal gRPC API
        :param certificate_file:      Certificate file for TLS
        :param node_id:         node_id of the node, for gRPC encryption
        """
        self.host = host
        self.port = port
        self.port_internal = port_internal
        self.node_id = node_id
        self.certificate_file = certificate_file

        if node_id:
            self.casperService = SecureGRPCService(
                host, port, casper_pb2_grpc.CasperServiceStub, node_id, certificate_file
            )
            self.controlService = SecureGRPCService(
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
        else:
            self.casperService = InsecureGRPCService(
                host, port, casper_pb2_grpc.CasperServiceStub
            )
            self.controlService = InsecureGRPCService(
                host, port_internal, control_pb2_grpc.ControlServiceStub
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
        # Convert from hex to binary.
        if from_addr and len(from_addr) == 64:
            from_addr = bytes.fromhex(from_addr)

        if from_addr and len(from_addr) != 32:
            raise Exception(f"from_addr must be 32 bytes")

        if payment_amount:
            payment_args = abi.ABI.args(
                [abi.ABI.big_int("amount", int(payment_amount))]
            )

        # Unless one of payment* options supplied use bundled standard-payment
        if not any((payment, payment_name, payment_hash, payment_uref)):
            payment = bundled_contract("standard_payment.wasm")

        session_options = (session, session_hash, session_name, session_uref)
        payment_options = (payment, payment_hash, payment_name, payment_uref)

        # Compatibility mode, should be removed when payment is obligatory
        if len(list(filter(None, payment_options))) == 0:
            logging.info("No payment contract provided, using session as payment")
            payment_options = session_options

        if len(list(filter(None, session_options))) != 1:
            raise TypeError(
                "deploy: only one of session, session_hash, session_name, session_uref must be provided"
            )

        if len(list(filter(None, payment_options))) != 1:
            raise TypeError(
                "deploy: only one of payment, payment_hash, payment_name, payment_uref must be provided"
            )

        # session_args must go to payment as well for now cause otherwise we'll get GASLIMIT error,
        # if payment is same as session:
        # https://github.com/CasperLabs/CasperLabs/blob/dev/casper/src/main/scala/io/casperlabs/casper/util/ProtoUtil.scala#L463
        body = consensus.Deploy.Body(
            session=_encode_contract(session_options, session_args),
            payment=_encode_contract(payment_options, payment_args),
        )

        header = consensus.Deploy.Header(
            account_public_key=from_addr
            or (public_key and crypto.read_pem_key(public_key)),
            timestamp=int(1000 * time.time()),
            gas_price=gas_price,
            body_hash=crypto.blake2b_hash(_serialize(body)),
            ttl_millis=ttl_millis,
            dependencies=dependencies
            and [bytes.fromhex(d) for d in dependencies]
            or [],
            chain_name=chain_name or "",
        )

        deploy_hash = crypto.blake2b_hash(_serialize(header))

        return consensus.Deploy(deploy_hash=deploy_hash, header=header, body=body)

    @api
    def sign_deploy(self, deploy, public_key, private_key_file):
        # import pdb; pdb.set_trace()
        # See if this is hex encoded
        try:
            public_key = bytes.fromhex(public_key)
        except TypeError:
            pass

        deploy.approvals.extend(
            [
                consensus.Approval(
                    approver_public_key=public_key,
                    signature=crypto.signature(private_key_file, deploy.deploy_hash),
                )
            ]
        )
        return deploy

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
    ):
        """
        Deploy a smart contract source file to Casper on an existing running node.
        The deploy will be packaged and sent as a block to the network depending
        on the configuration of the Casper instance.

        :param from_addr:     Purse address that will be used to pay for the deployment.
        :param gas_price:     The price of gas for this transaction in units dust/gas.
                              Must be positive integer.
        :param payment:       Path to the file with payment code.
        :param session:       Path to the file with session code.
        :param public_key:    Path to a file with public key (Ed25519)
        :param private_key:   Path to a file with private key (Ed25519)
        :param session_args:  List of ABI encoded arguments of session contract
        :param payment_args:  List of ABI encoded arguments of payment contract
        :param session-hash:  Hash of the stored contract to be called in the
                              session; base16 encoded.
        :param session-name:  Name of the stored contract (associated with the
                              executing account) to be called in the session.
        :param session-uref:  URef of the stored contract to be called in the
                              session; base16 encoded.
        :param payment-hash:  Hash of the stored contract to be called in the
                              payment; base16 encoded.
        :param payment-name:  Name of the stored contract (associated with the
                              executing account) to be called in the payment.
        :param payment-uref:  URef of the stored contract to be called in the
                              payment; base16 encoded.
        :ttl_millis:          Time to live. Time (in milliseconds) that the
                              deploy will remain valid for.
        :dependencies:        List of deploy hashes (base16 encoded) which
                              must be executed before this deploy.
        :chain_name:          Name of the chain to optionally restrict the
                              deploy from being accidentally included
                              anywhere else.
        :return:              Tuple: (deserialized DeployServiceResponse object, deploy_hash)
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

        pk = (
            (public_key and crypto.read_pem_key(public_key))
            or from_addr
            or crypto.private_to_public_key(private_key)
        )
        deploy = self.sign_deploy(deploy, pk, private_key)

        # TODO: Return only deploy_hash
        return self.send_deploy(deploy), deploy.deploy_hash

    @api
    def transfer(self, target_account_hex, amount, **deploy_args):
        target_account_bytes = bytes.fromhex(target_account_hex)
        deploy_args["session"] = bundled_contract("transfer_to_account.wasm")
        deploy_args["session_args"] = abi.ABI.args(
            [
                abi.ABI.account("account", target_account_bytes),
                abi.ABI.long_value("amount", amount),
            ]
        )
        _, deploy_hash_bytes = self.deploy(**deploy_args)
        return deploy_hash_bytes.hex()

    @api
    def send_deploy(self, deploy):
        # TODO: Deploy returns Empty, error handling via exceptions, apparently,
        # so no point in returning it.
        return self.casperService.Deploy(casper.DeployRequest(deploy=deploy))

    @api
    def showBlocks(self, depth: int = 1, max_rank=0, full_view=True):
        """
        Get slices of the DAG, going backwards, rank by rank.

        :param depth:     How many of the top ranks of the DAG to show.
        :param max_rank:  Maximum rank to go back from.
                          0 means go from the current tip of the DAG.
        :param full_view: Full view if True, otherwise basic.
        :return:          Generator of block info objects.
        """
        yield from self.casperService.StreamBlockInfos_stream(
            casper.StreamBlockInfosRequest(
                depth=depth,
                max_rank=max_rank,
                view=(
                    full_view and info.BlockInfo.View.FULL or info.BlockInfo.View.BASIC
                ),
            )
        )

    @api
    def showBlock(self, block_hash_base16: str, full_view=True):
        """
        Returns object describing a block known by Casper on an existing running node.

        :param block_hash_base16: hash of the block to be retrieved
        :param full_view:         full view if True, otherwise basic
        :return:                  object representing the retrieved block
        """
        return self.casperService.GetBlockInfo(
            casper.GetBlockInfoRequest(
                block_hash_base16=block_hash_base16,
                view=(
                    full_view and info.BlockInfo.View.FULL or info.BlockInfo.View.BASIC
                ),
            )
        )

    @api
    def propose(self):
        """"
        Propose a block using deploys in the pool.

        :return:    response object with block_hash
        """
        return self.controlService.Propose(control.ProposeRequest())

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
        block_infos = list(self.showBlocks(depth, full_view=False))
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

        yield self._call_dot(dot_dag_description, file_name(), file_format)
        previous_block_hashes = set(b.summary.block_hash for b in block_infos)
        while stream:
            time.sleep(delay_in_seconds)
            block_infos = list(self.showBlocks(depth, full_view=False))
            block_hashes = set(b.summary.block_hash for b in block_infos)
            if block_hashes != previous_block_hashes:
                dot_dag_description = vdag.generate_dot(
                    block_infos, show_justification_lines
                )
                yield self._call_dot(dot_dag_description, file_name(), file_format)
                previous_block_hashes = block_hashes

    def _call_dot(self, dot_dag_description, file_name, file_format):
        with tempfile.NamedTemporaryFile(mode="w") as f:
            f.write(dot_dag_description)
            f.flush()
            cmd = f"dot -T{file_format} -o {file_name} {f.name}"
            rc = os.system(cmd)
            if rc:
                raise Exception(f"Call to dot ({cmd}) failed with error code {rc}")
        print(f"Wrote {file_name}")
        return file_name

    @api
    def queryState(self, blockHash: str, key: str, path: str, keyType: str):
        """
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

        def key_variant(keyType):
            variant = self.STATE_QUERY_KEY_VARIANT.get(keyType.lower(), None)
            if variant is None:
                raise InternalError(
                    "query-state", f"{keyType} is not a known query-state key type"
                )
            return variant

        def encode_local_key(key: str) -> str:
            seed, rest = key.split(":")
            abi_encoded_rest = abi_byte_array(bytes.fromhex(rest)).hex()
            r = f"{seed}:{abi_encoded_rest}"
            logging.info(f"encode_local_key => {r}")
            return r

        key_value = encode_local_key(key) if keyType.lower() == "local" else key

        q = casper.StateQuery(key_variant=key_variant(keyType), key_base16=key_value)
        q.path_segments.extend([name for name in path.split("/") if name])
        return self.casperService.GetBlockState(
            casper.GetBlockStateRequest(block_hash_base16=blockHash, query=q)
        )

    @api
    def balance(self, address: str, block_hash: str):
        value = self.queryState(block_hash, address, "", "address")
        account = None
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

        mintPublic = urefs[0]

        mintPublicHex = mintPublic.key.uref.uref.hex()
        purseAddrHex = account.purse_id.uref.hex()
        localKeyValue = f"{mintPublicHex}:{purseAddrHex}"

        balanceURef = self.queryState(block_hash, localKeyValue, "", "local")
        balance = self.queryState(
            block_hash, balanceURef.key.uref.uref.hex(), "", "uref"
        )
        return int(balance.big_int.value)

    @api
    def showDeploy(self, deploy_hash_base16: str, full_view=True):
        """
        Retrieve information about a single deploy by hash.
        """
        return self.casperService.GetDeployInfo(
            casper.GetDeployInfoRequest(
                deploy_hash_base16=deploy_hash_base16,
                view=(
                    full_view
                    and info.DeployInfo.View.FULL
                    or info.DeployInfo.View.BASIC
                ),
            )
        )

    @api
    def showDeploys(self, block_hash_base16: str, full_view=True):
        """
        Get the processed deploys within a block.
        """
        yield from self.casperService.StreamBlockDeploys_stream(
            casper.StreamBlockDeploysRequest(
                block_hash_base16=block_hash_base16,
                view=(
                    full_view
                    and info.DeployInfo.View.FULL
                    or info.DeployInfo.View.BASIC
                ),
            )
        )

    def cli(self, *arguments) -> int:
        from . import cli

        return cli(
            "--host",
            self.host,
            "--port",
            self.port,
            "--port-internal",
            self.port_internal,
            *arguments,
        )


def hexify(o):
    """
    Convert protobuf message to text format with cryptographic keys and signatures in base 16.
    """
    return google.protobuf.text_format.MessageToString(o)


def bundled_contract(file_name):
    """
    Return path to contract file bundled with the package.
    """
    p = pkg_resources.resource_filename(__name__, file_name)
    if not os.path.exists(p):
        raise Exception(f"Missing bundled contract {file_name} ({p})")
    return p
