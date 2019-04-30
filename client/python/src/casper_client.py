#!/usr/bin/env python3
"""
CasperLabs Client API library and command line tool.
"""
import sys
import time
import argparse
import grpc
import functools


import CasperMessage_pb2
from CasperMessage_pb2_grpc import DeployServiceStub

DEFAULT_HOST='127.0.0.1'
DEFAULT_PORT=40401


class InternalError(Exception):
    """
    The only exception that API calls can throw.
    Internal errors like gRPC exceptions will be caught
    and this exception thrown instead, so the user does
    not have to worry about handling any other exceptions.
    """
    pass


def read_deploy_code(fileName: str):
    """
    Returns DeployCode object as defined in CasperMessage.proto
    with its attribute code populated with compiled WASM code
    read from the given file.

    Note: similarly as the Scala client, currently we don't allow 
    to pass anything in the args (ABI encoded arguments)
    attribute of DeployCode.

    :param fileName: path to file with compiled WASM code
    :return: a CasperMessage_pb2.DeployCode object
    """
    deployCode = CasperMessage_pb2.DeployCode()
    with open(fileName, "rb") as f:
        deployCode.code = f.read()
        return deployCode


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


class Node:
    """
    Helper class that is used by CasperClient implementation to reduce boilerplate code.
    """
    def __init__(self, client):
        self.client = client

    def __getattr__(self, name):
        address = self.client.host + ':' + str(self.client.port)

        def f(*args):
            with grpc.insecure_channel(address) as channel:
                return getattr(DeployServiceStub(channel), name)(*args)

        def g(*args):
            with grpc.insecure_channel(address) as channel:
                yield from getattr(DeployServiceStub(channel), name[:-1])(*args)

        return name.endswith('_') and g or f


class CasperClient:
    """
    gRPC CasperLabs client.
    """

    def __init__(self, host: str = DEFAULT_HOST, port: int = DEFAULT_PORT):
        """
        CasperLabs client's constructor.

        :param host:  Hostname or IP of node on which gRPC service is running
        :param port:  Port used for external gRPC API
        """
        self.host = host
        self.port = port
        self.node = Node(self)


    @guarded
    def deploy(self, from_: bytes, gas_limit: int, gas_price: int, payment: str, session: str, nonce: int=0):
        """
        Deploy a smart contract source file to Casper on an existing running node.
        The deploy will be packaged and sent as a block to the network depending
        on the configuration of the Casper instance.

        :param from:          Purse address that will be used to pay for the deployment.
        :param gas_limit:     The amount of gas to use for the transaction (unused gas
                              is refunded). Must be positive integer.
        :param gas_price:     The price of gas for this transaction in units dust/gas.
                              Must be positive integer.
        :param payment:       Path to the file with payment code.
        :param session:       Path to the file with session code.
        :param nonce:         This allows you to overwrite your own pending
#                             transactions that use the same nonce.
        :return:              deserialized DeployServiceResponse object
        """
        data = CasperMessage_pb2.DeployData()
        #bytes address = 1; // length 20 bytes
        data.address = from_
        #int64 timestamp = 2;
        data.timestamp = int(time.time())
        #DeployCode session = 3;
        data.session.CopyFrom(read_deploy_code(session))
        #DeployCode payment = 4;
        data.payment.CopyFrom(read_deploy_code(payment))
        #int64 gas_limit = 5;
        data.gas_limit = gas_limit
        #int64 gas_price = 6;
        data.gas_price = gas_price
        #int64 nonce = 7;
        data.nonce = nonce

        # TODO: Scala client does not set attributes below
        #string sig_algorithm = 8; // name of the algorithm used for signing
        #bytes signature = 9; // signature over hash of [(hash(session code), hash(payment code), nonce, timestamp, gas limit, gas rate)]

        # TODO: allow user to specify their public key [comment copied from Scala client source]
        #bytes user = 10; //public key
        return self.node.DoDeploy(data)

    @guarded
    def showBlocks(self, depth: int = 1):
        """
        Yields list of blocks in the current Casper view on an existing running node.

        :param depth: lists blocks to the given depth in terms of block height
        :return: generator of blocks
        """
        q = CasperMessage_pb2.BlocksQuery()
        q.depth = depth
        yield from self.node.showBlocks_(q)

    @guarded
    def showBlock(self, hash):
        """
        Return object describing a block known by Casper on an existing running node.

        :param hash:  hash of the block to be retrieved
        :return:      object representing the retrieved block
        """
        q = CasperMessage_pb2.BlockQuery()
        q.hash = hash
        return self.node.showBlock(q)

    @guarded
    def propose(self):
        """"
        Force a node to propose a block based on its accumulated deploys.

        :return:    response object
        """
        return self.node.createBlock(CasperMessage_pb2.google_dot_protobuf_dot_empty__pb2.Empty())

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
        q = CasperMessage_pb2.VisualizeDagQuery()
        q.depth = depth
        q.showJustificationLines = show_justification_lines
        return self.node.visualizeDag(q)

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
        q = CasperMessage_pb2.QueryStateRequest()
        q.block_hash = blockHash
        q.key_bytes = key
        q.key_variant = keyType
        q.path = path
        return self.node.queryState(q)


    @guarded
    def showMainChain(self, depth: int):
        """
        TODO: this is not implemented in the Scala client, need to find out docs.

        rpc showMainChain (BlocksQuery) returns (stream BlockInfoWithoutTuplespace) {}

        :param depth:
        :return:
        """

        q = CasperMessage_pb2.BlocksQuery()
        q.depth = depth
        yield from self.node.showMainChain_(q)


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
        q = CasperMessage_pb2.FindDeployInBlockQuery()
        q.user = user
        q.timestamp = timestamp
        return self.node.findBlockWithDeploy(q)


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
        print ('------------- block {} ---------------'.format(block.blockNumber))
        print (block)
        print ('-----------------------------------------------------\n')
        count += 1
    print ('count:', count)

def _show_block(response):
    if response.status != 'Success':
        print(response.status)
        return 1
    print(response)

@guarded_command
def deploy_command(casper_client, args):
    response = casper_client.deploy(getattr(args,'from'), args.gas_limit, args.gas_price, 
                                    args.payment, args.session, args.nonce)
    print (response.message)
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
    print (response.content)

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
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument('--help', action='help', default=argparse.SUPPRESS,
                        help='show this help message and exit')

    parser.add_argument('-h', '--host', required=False, default=DEFAULT_HOST, type=str,
                        help='Hostname or IP of node on which gRPC service is running.')
    parser.add_argument('-p', '--port', required=False, default=DEFAULT_PORT, type=int,
                        help='Port used for external gRPC API.')

    sp = parser.add_subparsers(help='Choose a request')

    deploy_parser = sp.add_parser('deploy', help='Deploy a smart contract source file to Casper on an existing running node. The deploy will be packaged and sent as a block to the network depending on the configuration of the Casper instance') 
    deploy_parser.add_argument('-f', '--from', required=True, type=lambda x: bytes(x, 'utf-8'),
                               help='Purse address that will be used to pay for the deployment.')
    deploy_parser.add_argument('-g', '--gas-limit', required=True, type=int,
                               help='The amount of gas to use for the transaction (unused gas is refunded). Must be positive integer.')
    deploy_parser.add_argument('--gas-price', required=True, type=int,
                               help='The price of gas for this transaction in units dust/gas. Must be positive integer.')
    deploy_parser.add_argument('-n', '--nonce', required=False, type=int, default=0,
                               help='This allows you to overwrite your own pending transactions that use the same nonce.')
    deploy_parser.add_argument('-p', '--payment', required=True, type=str,
                               help='Path to the file with payment code')
    deploy_parser.add_argument('-s', '--session', required=True, type=str,
                               help='Path to the file with session code')
    deploy_parser.set_defaults(function=deploy_command)

    propose_parser = sp.add_parser('propose', help='Force a node to propose a block based on its accumulated deploys.')
    propose_parser.set_defaults(function=propose_command)

    show_block_parser = sp.add_parser('show-block', help='View properties of a block known by Casper on an existing running node. Output includes: parent hashes, storage contents of the tuplespace.')
    show_block_parser.add_argument('hash', type=str, help='the hash value of the block')
    show_block_parser.set_defaults(function=show_block_command)

    show_blocks_parser = sp.add_parser('show-blocks',
                                       help='View list of blocks in the current Casper view on an existing running node.')
    show_blocks_parser.add_argument('-d', '--depth', required=True, type=int,
                                    help='lists blocks to the given depth in terms of block height')
    show_blocks_parser.set_defaults(function=show_blocks_command)
    
    vdag_parser = sp.add_parser('vdag', help='DAG in DOT format')
    vdag_parser.add_argument('-d', '--depth', required=True, type=int,
                             help='depth in terms of block height')
    vdag_parser.add_argument('-o', '--out', required=False, type=str,
                             help='output image filename, outputs to stdout if not specified, '
                                  + 'must end with one of the png, svg, svg_standalone, xdot, '
                                  + 'plain, plain_ext, ps, ps2, json, json0')
    vdag_parser.add_argument('-s', '--show-justification-lines', action='store_true',
                             help='if justification lines should be shown')
    vdag_parser.add_argument('--stream', required=False, choices=('single-output', 'multiple-outputs'),
                             help="subscribe to changes, '--out' has to be specified, valid values are 'single-output', 'multiple-outputs'")
    vdag_parser.set_defaults(function=vdag_command)

    query_state_parser = sp.add_parser('query-state', help='Query a value in the global state.')
    query_state_parser.add_argument('-b', '--block-hash', required=True, type=str,
                                    help='Hash of the block to query the state of')
    query_state_parser.add_argument('-k', '--key', required=True, type=str,
                                    help='Base16 encoding of the base key.')
    query_state_parser.add_argument('-p', '--path', required=True, type=str,
                                    help="Path to the value to query. Must be of the form 'key1/key2/.../keyn'")
    query_state_parser.add_argument('-t', '--type', required=True, choices=('hash', 'uref', 'address'),
                                    help="Type of base key. Must be one of 'hash', 'uref', 'address'")
    query_state_parser.set_defaults(function=query_state_command)


    show_main_chain_parser = sp.add_parser('show-main-chain', help='Show main chain')
    show_main_chain_parser.add_argument('-d', '--depth', required=True, type=int,
                                        help='depth in terms of block height')
    show_main_chain_parser.set_defaults(function=show_main_chain_command)

    find_block_with_deploy_parser = sp.add_parser('find-block-with-deploy', help='Find block with deploy.')
    find_block_with_deploy_parser.add_argument('-u', '--user', required=True, type=lambda x: bytes(x, 'utf-8'), help="User")
    find_block_with_deploy_parser.add_argument('-t', '--timestamp', required=True, type=int,
                                               help="Time in seconds since the epoch as an integer")
    find_block_with_deploy_parser.set_defaults(function=find_block_with_deploy_command)


    if len(sys.argv) < 2:
        parser.print_usage()
        sys.exit(1)

    args = parser.parse_args()
    sys.exit(args.function(CasperClient(args.host, args.port), args))


if __name__ == '__main__':
    command_line_tool()

