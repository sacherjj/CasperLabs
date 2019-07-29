from test.cl_node.docker_node import DockerNode
from test.cl_node.casperlabs_accounts import Account


def balance(node, account_address, block_hash):
    try:
        return node.client.get_balance(account_address, block_hash)
    except:
        return 0


def test_scala_client_balance(one_node_network):
    node: DockerNode = one_node_network.docker_nodes[0]

    accounts = [Account(i) for i in range(1, 4)]

    block_hash = list(node.p_client.show_blocks(1))[0].summary.block_hash.hex()

    initial = [balance(node, account.public_key_hex, block_hash) for account in accounts]

    # Perform multiple transfers with end result of Acct1 = 200, Acct2 = 100, Acct3 = 700
    hashes = node.transfer_to_accounts([(1, 1000), (2, 800, 1), (3, 700, 2)])

    assert node.d_client.get_balance(account_address=accounts[0].public_key_hex, block_hash=hashes[-1]) == initial[0] + 200
    assert node.d_client.get_balance(account_address=accounts[1].public_key_hex, block_hash=hashes[-1]) == initial[1] + 100
    assert node.d_client.get_balance(account_address=accounts[2].public_key_hex, block_hash=hashes[-1]) == initial[2] + 700
import pytest
from pathlib import Path

ffi_test_contracts = [
    ('getcallerdefine.wasm', 'getcallercall.wasm'),
    ('listknownurefsdefine.wasm', 'listknownurefscall.wasm'),
]

def docker_path(p):
    return Path(*(['/data'] + str(p).split('/')[-2:]))


def deploy_and_propose_expect_no_errors(node, contract):
    client = node.d_client

    block_hash = node.deploy_and_propose(session_contract=contract,
                                         payment_contract=contract,
                                         from_address=node.genesis_account.public_key_hex,
                                         public_key=docker_path(node.genesis_account.public_key_path),
                                         private_key=docker_path(node.genesis_account.private_key_path)) 
    r = client.show_deploys(block_hash)[0]
    assert r.is_error is False, f'error_message: {r.error_message}'


@pytest.mark.parametrize("define_contract, call_contract", ffi_test_contracts)
def test_get_caller(one_node_network, define_contract, call_contract):
    node = one_node_network.docker_nodes[0]
    deploy_and_propose_expect_no_errors(node, define_contract)
    deploy_and_propose_expect_no_errors(node, call_contract)
from test.cl_node.errors import NonZeroExitCodeError

import pytest

from .cl_node.wait import wait_for_genesis_block


@pytest.mark.parametrize("wasm", ["test_helloname.wasm", "old_wasm/test_helloname.wasm"])
def test_multiple_propose(one_node_network, wasm):
    """
    Feature file: propose.feature
    Scenario: Single node deploy and multiple propose generates an Exception.
    OP-182: First propose should be success, and subsequent propose calls should throw an error/exception.
    """
    node = one_node_network.docker_nodes[0]
    assert 'Success' in node.client.deploy(session_contract=wasm, payment_contract=wasm)
    assert 'Success' in node.client.propose()
    number_of_blocks = node.client.get_blocks_count(100)

    try:
        result = node.client.propose()
        assert False, "Second propose must not succeed, should throw"
    except NonZeroExitCodeError as e:
        assert e.exit_code == 1, "Second propose should fail"
    wait_for_genesis_block(node)

    # Number of blocks after second propose should not change
    assert node.client.get_blocks_count(100) == number_of_blocks
from .cl_node.errors import NonZeroExitCodeError

import pytest

# Examples of query-state executed with the Scala client that result in errors:

# CasperLabs/docker $ ./client.sh node-0 propose
# Response: Success! Block 9d38836598... created and added.

# CasperLabs/docker $ ./client.sh node-0 query-state --block-hash '"9d"' --key '"a91208047c"' --path file.xxx --type hash
# NOT_FOUND: Cannot find block matching hash "9d"

# CasperLabs/docker$ ./client.sh node-0 query-state --block-hash 9d --key '"a91208047c"' --path file.xxx --type hash
# INVALID_ARGUMENT: Key of type hash must have exactly 32 bytes, 5 =/= 32 provided.

# CasperLabs/docker$ ./client.sh node-0 query-state --block-hash 9d --key 3030303030303030303030303030303030303030303030303030303030303030 --path file.xxx --type hash
# INVALID_ARGUMENT: Value not found: " Hash([48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48])"


@pytest.fixture(scope='module')
def node(one_node_network):
    return one_node_network.docker_nodes[0]

@pytest.fixture(scope='module')
def client(node):
    return node.d_client

@pytest.fixture(scope='module')
def block_hash(node):
    return node.deploy_and_propose(session_contract="test_helloname.wasm", payment_contract="test_helloname.wasm")

block_hash_queries = [
    ({'block_hash': "9d", 'key': "a91208047c", 'path': "file.xxx", 'key_type': "hash"},
     "NOT_FOUND: Cannot find block matching"),

    ({                    'key': "a91208047c", 'path': "file.xxx", 'key_type': "hash"},
     "INVALID_ARGUMENT: Key of type hash must have exactly 32 bytes"),

    ({                                         'path': "file.xxx", 'key_type': "hash"},
     "INVALID_ARGUMENT: Value not found"),
]

@pytest.mark.parametrize("query, expected", block_hash_queries)
def test_query_state_error(node, client, block_hash, query, expected):
    if not 'block_hash' in query:
        query['block_hash'] = block_hash

    if not 'key' in query:
        query['key'] = node.from_address

    with pytest.raises(NonZeroExitCodeError) as excinfo:
        response = client.query_state(**query)
    assert expected in excinfo.value.output

import pytest
import logging
from pyblake2 import blake2b

from test import contract_hash
from test.cl_node.casperlabs_accounts import GENESIS_ACCOUNT


@pytest.fixture(scope='module')
def node(one_node_network):
    return one_node_network.docker_nodes[0]


@pytest.fixture(scope='module')
def client(node):
    return node.d_client


def test_revert_subcall(client, node):
    # This contract calls another contract that calls revert(2)
    block_hash = node.deploy_and_propose(session_contract='test_subcall_revert_define.wasm',
                                         payment_contract='test_subcall_revert_define.wasm')

    r = client.show_deploys(block_hash)[0]
    assert not r.is_error
    assert r.error_message == ''

    deploy_hash = r.deploy.deploy_hash

    r = client.show_deploy(deploy_hash)
    assert r.deploy.deploy_hash == deploy_hash

    # Help me figure out what subcall-revert-test/call/src/lib.rs should look like
    # TODO: function_counter 0 is a bug, to be fixed in EE.
    h = contract_hash(GENESIS_ACCOUNT.public_key_hex, 0, 0)
    logging.info("The expected contract hash is %s (%s)" % (list(h), h.hex()))

    block_hash = node.deploy_and_propose(session_contract='test_subcall_revert_call.wasm',
                                         payment_contract='test_subcall_revert_call.wasm')
    r = client.show_deploys(block_hash)[0]
    assert r.is_error
    assert r.error_message == "Exit code: 2"


def test_revert_direct(client, node):
    # This contract calls revert(1) directly
    block_hash = node.deploy_and_propose(session_contract='test_direct_revert_call.wasm',
                                         payment_contract='test_direct_revert_call.wasm')

    r = client.show_deploys(block_hash)[0]
    assert r.is_error
    assert r.error_message == "Exit code: 1"
from test.cl_node.casperlabsnode import extract_block_hash_from_propose_output
from test.cl_node.errors import NonZeroExitCodeError
import pytest

def test_deploy_with_valid_signature(one_node_network):
    """
    Feature file: deploy.feature
    Scenario: Deploy with valid signature
    """
    node0 = one_node_network.docker_nodes[0]
    client = node0.client
    client.deploy(session_contract='test_helloname.wasm',
                  payment_contract='test_helloname.wasm')

    block_hash = extract_block_hash_from_propose_output(client.propose())
    deploys = client.show_deploys(block_hash)
    assert deploys[0].is_error is False


def test_deploy_with_invalid_signature(one_node_network):
    """
    Feature file: deploy.feature
    Scenario: Deploy with invalid signature
    """

    node0 = one_node_network.docker_nodes[0]

    with pytest.raises(NonZeroExitCodeError):
        node0.client.deploy(session_contract='test_helloname.wasm',
                            payment_contract='test_helloname.wasm',
                            private_key="validator-0-private-invalid.pem",
                            public_key="validator-0-public-invalid.pem")
