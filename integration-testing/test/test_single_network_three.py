import logging
import pytest
import threading
import time
from functools import reduce
from itertools import count
from operator import add

from test.cl_node.casperlabsnode import extract_block_hash_from_propose_output
from test.cl_node.client_parser import parse_show_blocks
from test.cl_node.docker_node import DockerNode
from test.cl_node.errors import NonZeroExitCodeError
from test.cl_node.casperlabsnode import ( COMBINED_CONTRACT, COUNTER_CALL, HELLO_WORLD, MAILING_LIST_CALL)
from test.cl_node.wait import (wait_for_block_hash_propagated_to_all_nodes, wait_for_block_hashes_propagated_to_all_nodes)
from test import contract_hash
from threading import Thread
from time import sleep, time
from typing import List


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
                                              public_key=node.genesis_account.public_key_path,
                                              private_key=node.genesis_account.private_key_path)
    wait_for_block_hash_propagated_to_all_nodes(tnn.docker_nodes, block_hash)
    return tnn


def deploy_and_propose(node, contract):
    block_hash = node.deploy_and_propose(session_contract=contract,
                                         payment_contract=contract,
                                         from_address=node.genesis_account.public_key_hex,
                                         public_key=node.genesis_account.public_key_path,
                                         private_key=node.genesis_account.private_key_path)
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
def test_call_contracts_one_another(three_node_network_with_combined_contract, docker_client, contract, function_counter, path, expected):
    """
    Feature file: consensus.feature
    Scenario: Call contracts deployed on a node from another node.
    """

    nodes = three_node_network_with_combined_contract.docker_nodes

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
        kwargs['from_address'] = self.node.test_account.public_key_hex
        kwargs['public_key'] = self.node.test_account.public_key_path
        kwargs['private_key'] = self.node.test_account.private_key_path
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


# An explanation given by @Akosh about number of expected blocks.
# This is why the expected blocks are 4.
COMMENT_EXPECTED_BLOCKS = """
I think there is some randomness to it. --depth gives you the last
layers in the topological sorting of the DAG, which I believe is
stored by rank.
I'm not sure about the details, for example if we have
`G<-B1, G<-B2, B1<-B3` then G is rank 0, B1 and B2 are rank 1 and
B3 is rank 2. But we could also argue that a depth of 1 should
return B3 and B2.

Whatever --depth does, the layout of the DAG depends on how the
gossiping went when you did you proposals. If you did it real slow,
one by one, then you might have a chain of 8 blocks
(for example `G<-A1<-B1<-B2<-C1<-B3<-C2<-C3`), all linear;
but if you did it in perfect parallelism you could have all of them
branch out and be only 4 levels deep
(`G<-A1, G<-B1, G<-C1, [A1,B1,C1]<-C2`, etc).
"""


class DeployThread(threading.Thread):
    def __init__(self,
                 name: str,
                 node: DockerNode,
                 batches_of_contracts: List[List[str]],
                 max_attempts: int,
                 retry_seconds: int) -> None:
        threading.Thread.__init__(self)
        self.name = name
        self.node = node
        self.batches_of_contracts = batches_of_contracts
        self.max_attempts = max_attempts
        self.retry_seconds = retry_seconds
        self.block_hashes = []

    def run(self) -> None:
        for batch in self.batches_of_contracts:
            for contract in batch:
                assert 'Success' in self.node.client.deploy(session_contract=contract,
                                                            payment_contract=contract,
                                                            from_address=self.node.genesis_account.public_key_hex,
                                                            public_key=self.node.genesis_account.public_key_path,
                                                            private_key=self.node.genesis_account.private_key_path)

            block_hash = self.node.client.propose_with_retry(self.max_attempts, self.retry_seconds)
            self.block_hashes.append(block_hash)


@pytest.mark.parametrize("contract_paths,expected_deploy_counts_in_blocks", [
                        ([['test_helloname.wasm']], [1, 1, 1, 1])
])
# Nodes deploy one or more contracts followed by propose.
def test_multiple_deploys_at_once(three_node_network,
                                  contract_paths: List[List[str]], expected_deploy_counts_in_blocks):
    """
    Feature file : multiple_simultaneous_deploy.feature
    Scenario: Multiple simultaneous deploy after single deploy
    """
    nodes = three_node_network.docker_nodes
    # Wait for the genesis block reacing each node.

    deploy_threads = [DeployThread("node" + str(i + 1), node, contract_paths, max_attempts=5, retry_seconds=3)
                      for i, node in enumerate(nodes)]

    for t in deploy_threads:
        t.start()

    for t in deploy_threads:
        t.join()

    # See COMMENT_EXPECTED_BLOCKS
    block_hashes = reduce(add, [t.block_hashes for t in deploy_threads])
    wait_for_block_hashes_propagated_to_all_nodes(nodes, block_hashes)

    for node in nodes:
        blocks = parse_show_blocks(node.client.show_blocks(len(expected_deploy_counts_in_blocks) * 100))
        n_blocks = len(expected_deploy_counts_in_blocks)
        assert [b.summary.header.deploy_count for b in blocks][:n_blocks] == expected_deploy_counts_in_blocks, 'Unexpected deploy counts in blocks'
