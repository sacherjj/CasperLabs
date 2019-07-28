import time
import logging
import pytest
from itertools import count
from .cl_node.casperlabsnode import ( COMBINED_CONTRACT, COUNTER_CALL, HELLO_WORLD, MAILING_LIST_CALL)
from .cl_node.wait import wait_for_block_hash_propagated_to_all_nodes
from test import contract_hash
from pathlib import Path

def docker_path(p):
    return Path(*(['/data'] + str(p).split('/')[-2:]))


@pytest.fixture(scope='module')
def three_node_network_with_combined_contract(three_node_network):
    """
    Fixture of a network with deployed combined definitions contract,
    use later by tests in this module.
    """
    tnn = three_node_network
    bootstrap, node1, node2 = tnn.docker_nodes 
    node = bootstrap
    block_hash = bootstrap.deploy_and_propose(session_contract=COMBINED_CONTRACT, 
                                              payment_contract=COMBINED_CONTRACT,
                                              from_address=node.genesis_account.public_key_hex,
                                              public_key=docker_path(node.genesis_account.public_key_path),
                                              private_key=docker_path(node.genesis_account.private_key_path))
    wait_for_block_hash_propagated_to_all_nodes(tnn.docker_nodes, block_hash)
    return tnn


def deploy_and_propose(node, contract):
    block_hash = node.deploy_and_propose(session_contract=contract,
                                         payment_contract=contract,
                                         from_address=node.genesis_account.public_key_hex,
                                         public_key=docker_path(node.genesis_account.public_key_path),
                                         private_key=docker_path(node.genesis_account.private_key_path))
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

    from_address = nodes[0].genesis_account.public_key_hex

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

import logging
from threading import Thread
from time import sleep, time

from test.cl_node.errors import NonZeroExitCodeError
from test.cl_node.wait import wait_for_block_hashes_propagated_to_all_nodes
from test.cl_node.casperlabsnode import extract_block_hash_from_propose_output

CONTRACT_1 = 'old_wasm/helloname_invalid_just_1.wasm'
CONTRACT_2 = 'old_wasm/helloname_invalid_just_2.wasm'


class TimedThread(Thread):

    def __init__(self,
                 docker_node: 'DockerNode',
                 command_kwargs: dict,
                 start_time: float) -> None:
        Thread.__init__(self)
        self.name = docker_node.name
        self.node = docker_node
        self.kwargs = command_kwargs
        self.start_time = start_time

    def run(self) -> None:
        if self.start_time <= time():
            raise Exception(f'start_time: {self.start_time} is past current time: {time()}')

        while self.start_time > time():
            sleep(0.001)
        self.my_call(self.kwargs)

    def my_call(self, kwargs):
        raise NotImplementedError()


class DeployTimedTread(TimedThread):

    def my_call(self, kwargs):
        self.node.client.deploy(**kwargs)


class ProposeTimedThread(TimedThread):

    def my_call(self, kwargs):
        self.block_hash = None
        try:
            self.block_hash = extract_block_hash_from_propose_output(self.node.client.propose())
        except NonZeroExitCodeError:
            # Ignore error for no new deploys
            pass


def test_neglected_invalid_block(three_node_network):
    """
    Feature file: neglected_invalid_justification.feature
    Scenario: 3 Nodes doing simultaneous deploys and proposes do not have neglected invalid blocks
    """
    bootstrap, node1, node2 = three_node_network.docker_nodes

    for cycle_count in range(4):
        logging.info(f'DEPLOY_PROPOSE CYCLE COUNT: {cycle_count + 1}')
        start_time = time() + 1

        boot_deploy = DeployTimedTread(bootstrap,
                                       {'session_contract': CONTRACT_1,
                                        'payment_contract': CONTRACT_1},
                                       start_time)
        node1_deploy = DeployTimedTread(node1,
                                        {'session_contract': CONTRACT_2,
                                         'payment_contract': CONTRACT_2},
                                        start_time)
        node2_deploy = DeployTimedTread(node2,
                                        {'session_contract': CONTRACT_2,
                                         'payment_contract': CONTRACT_2},
                                        start_time)

        # Simultaneous Deploy
        node1_deploy.start()
        boot_deploy.start()
        node2_deploy.start()

        boot_deploy.join()
        node1_deploy.join()
        node2_deploy.join()

        start_time = time() + 1

        boot_deploy = ProposeTimedThread(bootstrap, {}, start_time)
        node1_deploy = ProposeTimedThread(node1, {}, start_time)
        node2_deploy = ProposeTimedThread(node2, {}, start_time)

        # Simultaneous Propose
        node1_deploy.start()
        boot_deploy.start()
        node2_deploy.start()

        boot_deploy.join()
        node1_deploy.join()
        node2_deploy.join()

    # Assure deploy and proposes occurred
    block_hashes = [h for h in [boot_deploy.block_hash, node1_deploy.block_hash, node2_deploy.block_hash] if h]
    wait_for_block_hashes_propagated_to_all_nodes(three_node_network.docker_nodes, block_hashes)

    assert ' for NeglectedInvalidBlock.' not in bootstrap.logs()
    assert ' for NeglectedInvalidBlock.' not in node1.logs()
    assert ' for NeglectedInvalidBlock.' not in node2.logs()
