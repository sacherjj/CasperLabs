import time
import logging
import pytest
from itertools import count
from .cl_node.casperlabsnode import ( COMBINED_CONTRACT, COUNTER_CALL, HELLO_WORLD, MAILING_LIST_CALL)
from .cl_node.wait import wait_for_blocks_count_at_least
from test import contract_hash


class BlockPropagatedToAllNodesChecker:
    def __init__(self, docker_nodes, initial_number_of_blocks):
        self.docker_nodes = docker_nodes
        self.number_of_blocks = initial_number_of_blocks

    def __call__(self):
        self.number_of_blocks += 1
        for n in self.docker_nodes:
            wait_for_blocks_count_at_least(n, self.number_of_blocks, self.number_of_blocks, n.timeout)


@pytest.fixture(scope='module')
def three_node_network_with_combined_contract(three_node_network_module_scope):
    """
    Fixture of a network with deployed combined definitions contract,
    use later by tests in this module.
    """
    tnn = three_node_network_module_scope
    bootstrap, node1, node2 = tnn.docker_nodes

    bootstrap.deploy_and_propose(session_contract = COMBINED_CONTRACT, payment_contract = COMBINED_CONTRACT)

    BlockPropagatedToAllNodesChecker(tnn.docker_nodes, 1)()

    return tnn


@pytest.fixture(scope='module')
def nodes(three_node_network_with_combined_contract):
    return three_node_network_with_combined_contract.docker_nodes


@pytest.fixture(scope='module')
def generated_hashes(nodes):
    """
    This fixture deploys & proposes test contracts on all nodes.
    Note, this happens when the combined contract definition is already deployed
    as part of the fixture three_node_network_with_combined_contract
    (via nodes)

    Returns dictionary mapping contract names to list of deployed block hashes
    on all nodes.
    """
    check_block_propagated = BlockPropagatedToAllNodesChecker(nodes, 2)

    def deploy_and_propose(node, contract):
        block_hash = node.deploy_and_propose(session_contract=contract, payment_contract=contract)
        check_block_propagated()
        deploys = node.client.show_deploys(block_hash)
        assert not deploys[0].is_error
        return block_hash

    return { contract_name: [deploy_and_propose(node, contract_name) for node in nodes]
             for contract_name in (COUNTER_CALL, MAILING_LIST_CALL, HELLO_WORLD) }


@pytest.fixture(scope='module')
def docker_client(three_node_network_with_combined_contract):
    return three_node_network_with_combined_contract.docker_client


expected_counter_result = count(1)

test_parameters = [
    (COUNTER_CALL, 1, "counter/count", lambda r: r.int_value == next(expected_counter_result)),
    (MAILING_LIST_CALL, 2, "mailing/list", lambda r: r.string_list.values == "CasperLabs"),
    (HELLO_WORLD, 0, "helloworld", lambda r: r.string_value == "Hello, World"),
]

@pytest.mark.parametrize("contract, function_counter, path, expected", test_parameters)
def test_call_contracts_one_another(nodes, docker_client, generated_hashes, contract, function_counter, path, expected):
    """
    Feature file: consensus.feature
    Scenario: Call contracts deployed on a node from another node.
    """

    # Help me figure out what hashes to put into the call contracts.
    # combined-contracts/define/src/lib.rs defines them;
    # the order is hello_name_ext, counter_ext, mailing_list_ext
    h = contract_hash(nodes[0].from_address(), 0, function_counter)
    logging.info("The expected contract hash for %s is %s (%s)" % (contract, list(h), h.hex()))

    def state(node, path, block_hash):
        return node.d_client.query_state(block_hash = block_hash, key = nodes[0].from_address(), key_type = "address", path = path)

    for node, block_hash in zip(nodes, generated_hashes[contract]):
        assert expected(state(node, path, block_hash))
