#!/usr/bin/env python3
"""
CasperLabs Client API library and command line tool.
"""
import sys
import time
import argparse
import grpc
import functools
import pathlib
from pyblake2 import blake2b
import ed25519
import struct
import json
from operator import add
from functools import reduce


# ~/CasperLabs/protobuf/io/casperlabs/casper/protocol/CasperMessage.proto
from . import CasperMessage_pb2
from .CasperMessage_pb2_grpc import DeployServiceStub

# ~/CasperLabs/protobuf/io/casperlabs/node/api/casper.proto
from . import casper_pb2 as casper
from .casper_pb2_grpc import CasperServiceStub

# ~/CasperLabs/protobuf/io/casperlabs/casper/consensus/consensus.proto
from . import consensus_pb2 as consensus

DEFAULT_HOST = '127.0.0.1'
DEFAULT_PORT = 40401
DEFAULT_INTERNAL_PORT = 40402


class ABI:
    """ Encode deploy args.
    """

    @staticmethod
    def u32(n: int):
        return struct.pack('<I', n)

    @staticmethod
    def u64(n: int):
        return struct.pack('<Q', n)
        
    @staticmethod
    def byte_array(a: bytes):
        return ABI.u32(len(a)) + a

    @staticmethod
    def account(a: bytes):
        if len(a) != 32:
            raise Error('Account must be 32 bytes long')
        return ABI.byte_array(a)

    @staticmethod
    def args(l: list):
        return ABI.u32(len(l)) + reduce(add, map(ABI.byte_array, l))

    @staticmethod
    def args_from_json(s: str) -> bytes:
        """
        Convert a string with JSON representation of deploy args to binary (ABI).

        The JSON should be a list of dictionaries {'type': 'value'} that represent type and value of the args,
        for example:

             [{"u32":1024}, {"account":"00000000000000000000000000000000"}, {"u64":1234567890}]
        """
        args = json.loads(s)

        for arg in args:
            if len(arg) != 1:
                raise Error(f'Wrong encoding of value in {arg}. Only one pair of type and value allowed.')

        def python_value(typ, value: str):
            if typ in ('u32', 'u64'):
                return int(value) 
            return bytearray(value, 'utf-8')

        def encode(typ: str, value: str) -> bytes:
            try:
                v = python_value(typ, value)
                return getattr(ABI, typ)(v)
            except KeyError:
                raise Error(f'Unknown type {typ} in {{typ: value}}')

        def only_one(arg):
            items = list(arg.items())
            if len(items) != 1:
                raise Error("Only one pair {'type', 'value'} allowed.")
            return items[0]

        return ABI.args([encode(*only_one(arg)) for arg in args]) 


class InternalError(Exception):
    """
    The only exception that API calls can throw.
    Internal errors like gRPC exceptions will be caught
    and this exception thrown instead, so the user does
    not have to worry about handling any other exceptions.
    """
    pass


def guarded(function):
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
        except Exception as e:
            raise InternalError() from e

    return wrapper


class CasperClient:
    """
    gRPC CasperLabs client.
    """

    def __init__(self, host: str = DEFAULT_HOST, port: int = DEFAULT_PORT, internal_port: int = DEFAULT_INTERNAL_PORT):
        """
        CasperLabs client's constructor.

        :param host:           Hostname or IP of node on which gRPC service is running
        :param port:           Port used for external gRPC API
        :param internal_port:  Port used for internal gRPC API
        """
        self.host = host
        self.port = port
        self.internal_port = internal_port

        client = self

        class GRPCService:

            def __init__(self, port, serviceStub):
                self.port = port
                self.serviceStub = serviceStub

            def __getattr__(self, name):
                address = client.host + ':' + str(self.port)

                def f(*args):
                    with grpc.insecure_channel(address) as channel:
                        return getattr(self.serviceStub(channel), name)(*args)

                def g(*args):
                    with grpc.insecure_channel(address) as channel:
                        yield from getattr(self.serviceStub(channel), name[:-1])(*args)

                return name.endswith('_') and g or f

        self.node = GRPCService(self.port, DeployServiceStub)
        self.casperService = GRPCService(self.port, CasperServiceStub)

    @guarded
    def deploy(self, from_addr: bytes = None, gas_limit: int = None, gas_price: int = None, 
               payment: str = None, session: str = None, nonce: int = 0,
               public_key: str = None, private_key: str = None, args: bytes = None):
        """
        Deploy a smart contract source file to Casper on an existing running node.
        The deploy will be packaged and sent as a block to the network depending
        on the configuration of the Casper instance.

        :param from_addr:     Purse address that will be used to pay for the deployment.
        :param gas_limit:     The amount of gas to use for the transaction (unused gas
                              is refunded). Must be positive integer.
        :param gas_price:     The price of gas for this transaction in units dust/gas.
                              Must be positive integer.
        :param payment:       Path to the file with payment code.
        :param session:       Path to the file with session code.
        :param nonce:         This allows you to overwrite your own pending
                              transactions that use the same nonce.
        :param public_key:    Path to a file with public key (Ed25519)
        :param private_key:   Path to a file with private key (Ed25519)
        :param args:          List of ABI encoded arguments
        :return:              Tuple: (deserialized DeployServiceResponse object, deploy_hash)
        """

        def hash(data: bytes) -> bytes:
            h = blake2b(digest_size=32)
            h.update(data)
            return h.digest()

        def read_binary(file_name: str):
            with open(file_name, 'rb') as f:
                return f.read()

        def read_code(file_name: str, abi_encoded_args: bytes = None):
            return consensus.Deploy.Code(code = read_binary(file_name),
                                         args = abi_encoded_args)


        def sign(data: bytes):
            return (private_key
                    and consensus.Signature(sig_algorithm = 'ed25519',
                                            sig = ed25519.SigningKey(read_binary(private_key)).sign(data, encoding='base16')))

        def serialize(o) -> bytes:
            return o.SerializeToString()

        # args must go to payment as well for now cause otherwise we'll get GASLIMIT error:
        # https://github.com/CasperLabs/CasperLabs/blob/dev/casper/src/main/scala/io/casperlabs/casper/util/ProtoUtil.scala#L463
        body = consensus.Deploy.Body(session = read_code(session, args),
                                     payment = read_code(payment, payment == session and args or None))

        account_public_key = public_key and read_binary(public_key)
        header = consensus.Deploy.Header(account_public_key = account_public_key, 
                                         nonce = nonce,
                                         timestamp = int(time.time()),
                                         gas_price = gas_price,
                                         body_hash = hash(serialize(body)))

        deploy_hash = hash(serialize(header))
        d = consensus.Deploy(deploy_hash = deploy_hash,
                             approvals = [consensus.Approval(approver_public_key = account_public_key,
                                                             signature = sign(deploy_hash))]
                                         if account_public_key
                                         else [],
                             header = header,
                             body = body)

        return self.casperService.Deploy(casper.DeployRequest(deploy = d)), deploy_hash


    @guarded
    def showBlocks(self, depth: int = 1):
        """
        Yields list of blocks in the current Casper view on an existing running node.

        :param depth: lists blocks to the given depth in terms of block height
        :return: generator of blocks
        """
        yield from self.node.showBlocks_(CasperMessage_pb2.BlocksQuery(depth=depth))

    @guarded
    def showBlock(self, hash: str):
        """
        Return object describing a block known by Casper on an existing running node.

        :param hash:  hash of the block to be retrieved
        :return:      object representing the retrieved block
        """
        return self.node.showBlock(CasperMessage_pb2.BlockQuery(hash=hash))

    @guarded
    def propose(self):
        """"
        Force a node to propose a block based on its accumulated deploys.

        :return:    response object
        """
        return self.node.createBlock(CasperMessage_pb2.empty__pb2.Empty())

    @guarded
    def visualizeDag(self, depth: int, out: str = None, show_justification_lines: bool = False, stream: str = None):
        """
        Retrieve DAG in DOT format.

        :param depth:                     depth in terms of block height
        :param out:                       output image filename, outputs to stdout if
                                          not specified, must end with one of the png,
                                          svg, svg_standalone, xdot, plain, plain_ext,
                                          ps, ps2, json, json0
        :param show_justification_lines:  if justification lines should be shown
        :param stream:                    subscribe to changes, 'out' has to specified,
                                          valid values are 'single-output', 'multiple-outputs'
        :return:                          VisualizeBlocksResponse object
        """
        # TODO: handle stream parameter
        return self.node.visualizeDag(
            CasperMessage_pb2.VisualizeDagQuery(depth=depth, showJustificationLines=show_justification_lines))

    @guarded
    def queryState(self, blockHash: str, key: str, path: str, keyType: str):
        """
        Query a value in the global state.

        :param blockHash:         Hash of the block to query the state of
        :param key:               Base16 encoding of the base key
        :param path:              Path to the value to query. Must be of the form
                                  'key1/key2/.../keyn'
        :param keyType:           Type of base key. Must be one of 'hash', 'uref',
                                  'address'.
        :return:                  QueryStateResponse object
        """
        def key_variant(keyType):
            return {'hash': casper.StateQuery.KeyVariant.HASH,
                    'uref': casper.StateQuery.KeyVariant.UREF,
                    'address': casper.StateQuery.KeyVariant.ADDRESS}[keyType]

        def path_segments(path):
            return path.split('/')

        return self.casperService.GetBlockState(
            casper.GetBlockStateRequest(block_hash_base16 = blockHash,
                                        query = casper.StateQuery(
                                                    key_variant = key_variant(keyType),
                                                    key_base16 = key,
                                                    path_segments = path_segments(path))))

    @guarded
    def showMainChain(self, depth: int):
        """
        TODO: this is not implemented in the Scala client, need to find out docs.

        rpc showMainChain (BlocksQuery) returns (stream BlockInfoWithoutTuplespace) {}

        :param depth:
        :return:
        """
        yield from self.node.showMainChain_(CasperMessage_pb2.BlocksQuery(depth=depth))

    @guarded
    def findBlockWithDeploy(self, user: bytes, timestamp: int):
        """
        TODO: this is not implemented in the Scala client, need to find out docs.

        rpc findBlockWithDeploy (FindDeployInBlockQuery) returns (BlockQueryResponse) {}

        message FindDeployInBlockQuery {
            bytes user = 1;
           int64 timestamp = 2;
        }

        :param user:
        :param timestamp:
        :return:
        """
        return self.node.findBlockWithDeploy(CasperMessage_pb2.FindDeployInBlockQuery(user=user, timestamp=timestamp))


def guarded_command(function):
    """
    Decorator of functions that implement CLI commands.

    Occasionally the node can throw some exceptions instead of properly sending us a response,
    those will be deserialized on our end and rethrown by the gRPC layer.
    In this case we want to catch the exception and return a non-zero return code to the shell.

    :param function:  function to be decorated
    :return:
    """
    @functools.wraps(function)
    def wrapper(*args, **kwargs):
        try:
            rc = function(*args, **kwargs)
            # Generally the CLI commands are assumed to succeed if they don't throw,
            # but they can also return a positive error code if they need to.
            if rc is not None:
                return rc
            return 0
        except Exception as e:
            print(str(e), file=sys.stderr)
            return 1

    return wrapper


def _show_blocks(response):
    count = 0
    for block in response:
        print('------------- block {} ---------------'.format(block.blockNumber))
        print(block)
        print('-----------------------------------------------------\n')
        count += 1
    print('count:', count)


def _show_block(response):
    if response.status != 'Success':
        print(response.status)
        return 1
    print(response)


@guarded_command
def deploy_command(casper_client, args):
    response, deploy_hash = casper_client.deploy(getattr(args,'from'),
                                                 args.gas_limit,
                                                 args.gas_price, 
                                                 args.payment,
                                                 args.session,
                                                 args.nonce,
                                                 args.public_key or None,
                                                 args.private_key or None,
                                                 args.args and ABI.args_from_json(args.args) or None)
    print (f'{response.message}. Deploy hash: {deploy_hash}')
    if not response.success:
        return 1


@guarded_command
def propose_command(casper_client, args):
    response = casper_client.propose()
    print(response.message)
    if not response.success:
        return 1


@guarded_command
def show_block_command(casper_client, args):
    response = casper_client.showBlock(args.hash)
    return _show_block(response)


@guarded_command
def show_blocks_command(casper_client, args):
    response = casper_client.showBlocks(args.depth)
    _show_blocks(response)


@guarded_command
def vdag_command(casper_client, args):
    response = casper_client.visualizeDag(args.depth)
    print(response.content)


@guarded_command
def query_state_command(casper_client, args):
    response = casper_client.queryState(args.block_hash, args.key, args.path, getattr(args, 'type'))
    print(response.result)


@guarded_command
def show_main_chain_command(casper_client, args):
    response = casper_client.showMainChain(args.depth)
    _show_blocks(response)


@guarded_command
def find_block_with_deploy_command(casper_client, args):
    response = casper_client.findBlockWithDeploy(args.user, args.timestamp)
    return _show_block(response)


def command_line_tool():
    """
    Parse command line and call an appropriate command.
    """

    class Parser:
        def __init__(self):
            self.parser = argparse.ArgumentParser(add_help=False)
            self.parser.add_argument('--help', action='help', default=argparse.SUPPRESS,
                                     help='show this help message and exit')
            self.parser.add_argument('-h', '--host', required=False, default=DEFAULT_HOST, type=str,
                                     help='Hostname or IP of node on which gRPC service is running.')
            self.parser.add_argument('-p', '--port', required=False, default=DEFAULT_PORT, type=int,
                                     help='Port used for external gRPC API.')
            self.parser.add_argument('--internal-port', required=False, default=DEFAULT_INTERNAL_PORT, type=int,
                                     help='Port used for internal gRPC API.')
            self.sp = self.parser.add_subparsers(help='Choose a request')

        def addCommand(self, command: str, function, help, arguments):
            command_parser = self.sp.add_parser(command, help=help)
            command_parser.set_defaults(function=function)
            for (args, options) in arguments:
                command_parser.add_argument(*args, **options)

        def run(self):
            if len(sys.argv) < 2:
                self.parser.print_usage()
                return 1

            args = self.parser.parse_args()
            return args.function(CasperClient(args.host, args.port, args.internal_port), args)

    parser = Parser()
    parser.addCommand('deploy', deploy_command, 'Deploy a smart contract source file to Casper on an existing running node. The deploy will be packaged and sent as a block to the network depending on the configuration of the Casper instance',
                      [[('-f', '--from'), dict(required=True, type=lambda x: bytes(x, 'utf-8'), help='Purse address that will be used to pay for the deployment.')],
                       [('-g', '--gas-limit'), dict(required=True, type=int, help='[Deprecated] The amount of gas to use for the transaction (unused gas is refunded). Must be positive integer.')],
                       [('--gas-price',), dict(required=True, type=int, help='The price of gas for this transaction in units dust/gas. Must be positive integer.')],
                       [('-n', '--nonce'), dict(required=False, type=int, default=0, help='This allows you to overwrite your own pending transactions that use the same nonce.')],
                       [('-p', '--payment'), dict(required=True, type=str, help='Path to the file with payment code')],
                       [('-s', '--session'), dict(required=True, type=str, help='Path to the file with session code')],
                       [('--args'), dict(required=False, type=str, help='JSON encoded list of args, e.g.: [{"u32":1024},{"u64":12}]')],
                       [('--private-key',), dict(required=True, type=str, help='Path to the file with account public key (Ed25519)')],
                       [('--public-key',), dict(required=True, type=str, help='Path to the file with account private key (Ed25519)')]])

    parser.addCommand('propose', propose_command, 'Force a node to propose a block based on its accumulated deploys.', [])

    parser.addCommand('show-block', show_block_command, 'View properties of a block known by Casper on an existing running node. Output includes: parent hashes, storage contents of the tuplespace.',
                      [[('hash',), dict(type=str, help='the hash value of the block')]])

    parser.addCommand('show-blocks', show_blocks_command, 'View list of blocks in the current Casper view on an existing running node.',
                      [[('-d', '--depth'), dict(required=True, type=int, help='depth in terms of block height')]])


    parser.addCommand('vdag', vdag_command, 'DAG in DOT format',
                      [[('-d', '--depth'), dict(required=True, type=int, help='depth in terms of block height')],
                       [('-o', '--out'), dict(required=False, type=str, help='output image filename, outputs to stdout if not specified, must end with one of the png, svg, svg_standalone, xdot, plain, plain_ext, ps, ps2, json, json0')],
                       [('-s', '--show-justification-lines'), dict(action='store_true', help='if justification lines should be shown')],
                       [('--stream',), dict(required=False, choices=('single-output', 'multiple-outputs'), help="subscribe to changes, '--out' has to be specified, valid values are 'single-output', 'multiple-outputs'")]])

    parser.addCommand('query-state', query_state_command, 'Query a value in the global state.',
                      [[('-b', '--block-hash'), dict(required=True, type=str, help='Hash of the block to query the state of')],
                       [('-k', '--key'), dict(required=True, type=str, help='Base16 encoding of the base key')],
                       [('-p', '--path'), dict(required=True, type=str, help="Path to the value to query. Must be of the form 'key1/key2/.../keyn'")],
                       [('-t', '--type'), dict(required=True, choices=('hash', 'uref', 'address'), help="Type of base key. Must be one of 'hash', 'uref', 'address'")]])

    parser.addCommand('show-main-chain', show_main_chain_command, 'Show main chain',
                      [[('-d', '--depth'), dict(required=True, type=int, help='depth in terms of block height')]])

    parser.addCommand('find-block-with-deploy', find_block_with_deploy_command, 'Find block with deploy',
                      [[('-u', '--user'), dict(required=True, type=lambda x: bytes(x, 'utf-8'), help="User")],
                       [('-t', '--timestamp'), dict(required=True, type=int, help="Time in seconds since the epoch as an integer")]])

    sys.exit(parser.run())


if __name__ == '__main__':
    command_line_tool()
