import pytest
import logging
from pyblake2 import blake2b

from test import contract_hash
from test.cl_node.casperlabs_accounts import GENESIS_ACCOUNT


@pytest.fixture(scope='module')
def node(one_node_network_module_scope):
    return one_node_network_module_scope.docker_nodes[0]


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
