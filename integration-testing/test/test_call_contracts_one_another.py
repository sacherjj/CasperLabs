import time
from itertools import count
import pytest
from .cl_node.casperlabsnode import ( COMBINED_CONTRACT, COUNTER_CALL, HELLO_WORLD, MAILING_LIST_CALL, get_contract_state)
from .cl_node.wait import wait_for_blocks_count_at_least


class BlockPropagatedToAllNodesChecker:
    def __init__(self, docker_nodes, number_of_blocks):
        self.docker_nodes = docker_nodes
        self.number_of_blocks = number_of_blocks

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
        return block_hash

    return { contract_name: [deploy_and_propose(node, contract_name) for node in nodes]
             for contract_name in (COUNTER_CALL, MAILING_LIST_CALL, HELLO_WORLD) }


@pytest.fixture(scope='module')
def docker_client(three_node_network_with_combined_contract):
    return three_node_network_with_combined_contract.docker_client


expected_result = count(1)

test_parameters = [
    (COUNTER_CALL, "counter/count", lambda: bytes(f'int_value: {next(expected_result)}\n\n', 'utf-8')),
    (MAILING_LIST_CALL, "mailing/list", lambda: bytes('string_list {\n  values: "CasperLabs"\n}\n\n', 'utf-8')),
    (HELLO_WORLD, "helloworld", lambda: b'string_value: "Hello, World"\n\n'),
]

@pytest.mark.parametrize("contract, path, expected", test_parameters)
def test_call_contracts_one_another(nodes, docker_client, generated_hashes, contract, path, expected):
    """
    Feature file: consensus.feature
    Scenario: Call contracts deployed on a node from another node.
    """

    def state(node, path, block_hash):
        return get_contract_state(docker_client = docker_client, port = 40401, _type = "address",
                                  key = 3030303030303030303030303030303030303030303030303030303030303030,
                                  network_name = node.network, target_host_name = node.name, path = path,
                                  block_hash = block_hash)


    for node, block_hash in zip(nodes, generated_hashes[contract]):
        output = state(node, path, block_hash)
        assert expected() == output
