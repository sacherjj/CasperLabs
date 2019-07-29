
import pytest
from .cl_node.client_parser import parse_show_blocks

"""
Test account state retrieval with query-state.

Example output of the Scala client:

account {
  public_key: "3030303030303030303030303030303030303030303030303030303030303030"
  nonce: 1
  purse_id {
    uref: "0000000000000000000000000000000000000000000000000000000000000000"
    access_rights: READ_ADD_WRITE
  }
  associated_keys {
    public_key: "3030303030303030303030303030303030303030303030303030303030303030"
    weight: 1
  }
  action_thresholds {
    deployment_threshold: 1
    key_management_threshold: 1
  }
  account_activity {
    key_management_last_used: 0
    deployment_last_used: 0
    inactivity_period_limit: 100
  }
}

"""


def test_account_state(one_node_network):
    node = one_node_network.docker_nodes[0]

    def account_state(block_hash):
        return node.d_client.query_state(block_hash=block_hash, key_type='address', key=node.from_address, path='')

    block_hash = node.deploy_and_propose(session_contract="test_counterdefine.wasm", payment_contract="test_counterdefine.wasm")
    deploys = node.client.show_deploys(block_hash)
    assert not deploys[0].is_error

    response = account_state(block_hash)
    assert response.account.nonce == 1, str(response)

    block_hash = node.deploy_and_propose(session_contract="test_countercall.wasm", payment_contract="test_countercall.wasm")
    response = account_state(block_hash)
    assert response.account.nonce == 2, str(response)



from pytest import raises

from test.cl_node.docker_node import DockerNode
from test.cl_node.casperlabs_accounts import Account


def test_transfer_with_overdraft(one_node_network):

    def account_state(block_hash, account):
        return node.d_client.query_state(block_hash=block_hash, key_type='address', key=account, path='')

    acct1 = Account(1)
    acct2 = Account(2)

    node: DockerNode = one_node_network.docker_nodes[0]
    # Transfer 1000000 from genesis... to acct1...
    block_hash = node.transfer_to_account(to_account_id=1, amount=1000000, from_account_id='genesis')

    deploys = node.client.show_deploys(block_hash)
    assert not deploys[0].is_error, f"error_message: {deploys[0].error_message}"

    # Response not used, but assures account exist
    _ = account_state(block_hash, acct1.public_key_hex)

    # Should error as account doesn't exist.
    with raises(Exception):
        _ = account_state(block_hash, acct2.public_key_hex)

    # No API currently exists for getting balance to check transfer.
    # Transfer 750000 from acct1... to acct2...
    block_hash = node.transfer_to_account(to_account_id=2, amount=750000, from_account_id=1)

    deploys = node.client.show_deploys(block_hash)
    assert not deploys[0].is_error, f"error_message: {deploys[0].error_message}"

    # Response not used, but assures account exist
    _ = account_state(block_hash, acct2.public_key_hex)

    # Transfer 750000000000 from acct1... to acct2...
    # Should fail with acct1 overdrawn.   Requires assert in contract to generate is_error.
    with raises(Exception):
        _ = node.transfer_to_account(to_account_id=2, amount=750000000000, from_account_id=1)


def test_transfer_to_accounts(one_node_network):
    node: DockerNode = one_node_network.docker_nodes[0]
    # Perform multiple transfers with end result of Acct1 = 100, Acct2 = 100, Acct3 = 800
    node.transfer_to_accounts([(1, 1000), (2, 900, 1), (3, 800, 2)])
    with raises(Exception):
        # Acct 1 has not enough funds so it should fail
        node.transfer_to_account(to_account_id=4, amount=100000000000, from_account_id=1)
    node.transfer_to_account(to_account_id=4, amount=100, from_account_id=2)
    # TODO: Improve checks once balance is easy to read.

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
from test.cl_node.client_parser import parse_show_blocks
from test.cl_node.errors import NonZeroExitCodeError
from test.cl_node.casperlabsnode import extract_block_hash_from_propose_output
from typing import List
import pytest


"""
Feature file: ~/CasperLabs/integration-testing/features/deploy.feature
"""

def deploy_and_propose(node, contract, nonce=None):
    node.client.deploy(session_contract=contract,
                       payment_contract=contract,
                       nonce=nonce)
    return extract_block_hash_from_propose_output(node.client.propose())


def deploy(node, contract, nonce):
    message = node.client.deploy(session_contract=contract, payment_contract=contract, nonce=nonce)
    assert 'Success!' in message
    return message.split()[2]


def propose(node):
    return extract_block_hash_from_propose_output(node.client.propose())


def deploy_hashes(node, block_hash):
    return set(d.deploy.deploy_hash for d in node.client.show_deploys(block_hash))


@pytest.mark.parametrize("contract", ['test_helloname.wasm',])
def test_deploy_without_nonce(node, contract: str):
    """
    Feature file: deploy.feature
    Scenario: Deploy without nonce
    """
    with pytest.raises(NonZeroExitCodeError):
        deploy_and_propose(node, contract, '')


@pytest.mark.parametrize("contracts", [['test_helloname.wasm', 'test_helloworld.wasm', 'test_counterdefine.wasm']])
def test_deploy_with_lower_nonce(node, contracts: List[str]):
    """
    Feature file: deploy.feature
    Scenario: Deploy with lower nonce
    """
    for contract in contracts:
        deploy_and_propose(node, contract)

    with pytest.raises(NonZeroExitCodeError):
        deploy_and_propose(node, contract, 2)



@pytest.mark.parametrize("contracts", [['test_helloname.wasm', 'test_helloworld.wasm', 'test_counterdefine.wasm']])
def test_deploy_with_higher_nonce(node, contracts: List[str]):
    """
    Feature file: deploy.feature

    Scenario: Deploy with higher nonce
    """
    # Deploy successfully with nonce 1 => Nonce is 1 for account.
    deploy_and_propose(node, contracts[0], 1)

    deploy_hash = deploy(node, contracts[2], 3)

    with pytest.raises(NonZeroExitCodeError):
        node.client.propose()

    deploy_and_propose(node, contracts[1], 2)

    # The deploy with nonce 3 can be proposed now.
    block_hash = propose(node)  
    assert deploy_hash in deploy_hashes(node, block_hash)


@pytest.mark.parametrize("contracts", [['test_helloname.wasm', 'test_helloworld.wasm', 'test_counterdefine.wasm', 'test_countercall.wasm']])
def test_deploy_with_higher_nonce_does_not_include_previous_deploy(node, contracts: List[str]):
    """
    Feature file: deploy.feature

    Scenario: Deploy with higher nonce and created block does not include previously deployed contract.
    """
    # Deploy successfully with nonce 1 => Nonce is 1 for account.
    deploy_hash = deploy(node, contracts[0], 1)
    block_hash = propose(node)
    assert deploy_hash in deploy_hashes(node, block_hash)

    deploy_hash4 = deploy(node, contracts[1], 4)

    with pytest.raises(NonZeroExitCodeError):
        propose(node)

    deploy_hash2 = deploy(node, contracts[2], 2)
    # The deploy with nonce 4 cannot be proposed now. It will be in the deploy buffer but does not include
    # in the new block created now.
    block_hash = propose(node)
    deploys = deploy_hashes(node, block_hash)
    assert deploy_hash4 not in deploys
    assert deploy_hash2 in deploys

    deploy_hash3 = deploy(node, contracts[3], 3)
    block_hash = propose(node)
    assert deploy_hash3 in deploy_hashes(node, block_hash)

    block_hash = propose(node)
    assert deploy_hash4 in deploy_hashes(node, block_hash)
