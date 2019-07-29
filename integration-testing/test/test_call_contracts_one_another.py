import time
import logging
import pytest
from itertools import count
from .cl_node.casperlabsnode import ( COMBINED_CONTRACT, COUNTER_CALL, HELLO_WORLD, MAILING_LIST_CALL)
from .cl_node.wait import wait_for_block_hash_propagated_to_all_nodes
from test import contract_hash


@pytest.fixture(scope='module')
def three_node_network_with_combined_contract(three_node_network_module_scope):
    """
    Fixture of a network with deployed combined definitions contract,
    use later by tests in this module.
    """
    tnn = three_node_network_module_scope
    bootstrap, node1, node2 = tnn.docker_nodes
    block_hash = bootstrap.deploy_and_propose(session_contract = COMBINED_CONTRACT, payment_contract = COMBINED_CONTRACT)
    wait_for_block_hash_propagated_to_all_nodes(tnn.docker_nodes, block_hash)
    return tnn


@pytest.fixture(scope='module')
def nodes(three_node_network_with_combined_contract):
    return three_node_network_with_combined_contract.docker_nodes


def deploy_and_propose(node, contract):
    block_hash = node.deploy_and_propose(session_contract=contract, payment_contract=contract)
    deploys = node.client.show_deploys(block_hash)
    for deploy in deploys:
        assert deploy.is_error is False
    return block_hash


@pytest.fixture(scope='module')
def docker_client(three_node_network_with_combined_contract):
    return three_node_network_with_combined_contract.docker_client


expected_counter_result = count(1)

test_parameters = [
    (MAILING_LIST_CALL, 2, "mailing/list", lambda r: r.string_list.values == "CasperLabs"),
    (COUNTER_CALL, 1, "counter/count", lambda r: r.int_value == next(expected_counter_result)),
    (HELLO_WORLD, 0, "helloworld", lambda r: r.string_value == "Hello, World"),
]


@pytest.mark.parametrize("contract, function_counter, path, expected", test_parameters)
def test_call_contracts_one_another(nodes, docker_client, contract, function_counter, path, expected):
    """
    Feature file: consensus.feature
    Scenario: Call contracts deployed on a node from another node.
    """

    from_address = nodes[0].from_address

    # Help me figure out what hashes to put into the call contracts.
    # combined-contracts/define/src/lib.rs defines them;
    # the order is hello_name_ext, counter_ext, mailing_list_ext
    # h = contract_hash(from_address, 0, function_counter)
    # logging.info("The expected contract hash for %s is %s (%s)" % (contract, list(h), h.hex()))

    def state(node, path, block_hash):
        return node.d_client.query_state(block_hash=block_hash, key=from_address, key_type="address", path=path)

    for node in nodes:
        block_hash = deploy_and_propose(node, contract)
        wait_for_block_hash_propagated_to_all_nodes(nodes, block_hash)
        assert expected(state(node, path, block_hash))
