import threading
import pytest
from typing import List

from test.cl_node.docker_node import DockerNode
from test.cl_node.client_parser import parse_show_blocks
from .cl_node.wait import (
    wait_for_blocks_count_at_least,
)


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
    def __init__(self, name: str, node: DockerNode, batches_of_contracts: List[List[str]]) -> None:
        threading.Thread.__init__(self)
        self.name = name
        self.node = node
        self.batches_of_contracts = batches_of_contracts

    def run(self) -> None:
        for batch in self.batches_of_contracts:
            for contract in batch:
                assert 'Success' in self.node.client.deploy(session_contract=contract, payment_contract=contract)
            self.node.client.propose()


@pytest.mark.parametrize("contract_paths,expected_deploy_counts_in_blocks", [
                         ([['test_helloname.wasm'],['test_helloworld.wasm']], [1, 1, 1, 1, 0]),
])
# Nodes deploy one or more contracts followed by propose.
def test_multiple_deploys_at_once(three_node_network,
                                  contract_paths: List[List[str]], expected_deploy_counts_in_blocks):
    """
    Feature file : multiple_simultaneous_deploy.feature
    Scenario: Multiple simultaneous deploy after single deploy
    """
    nodes = three_node_network.docker_nodes

    deploy_threads = [DeployThread("node" + str(i+1), node, contract_paths)
                      for i, node in enumerate(nodes)]

    for t in deploy_threads:
        t.start()

    for t in deploy_threads:
        t.join()

    # See COMMENT_EXPECTED_BLOCKS
    for node in nodes:
        wait_for_blocks_count_at_least(node, len(expected_deploy_counts_in_blocks), len(expected_deploy_counts_in_blocks) * 2, node.timeout)

    for node in nodes:
        blocks = parse_show_blocks(node.client.show_blocks(len(expected_deploy_counts_in_blocks) * 100))
        n_blocks = len(expected_deploy_counts_in_blocks)
        assert [b.deploy_count for b in blocks][:n_blocks] == expected_deploy_counts_in_blocks, \
               'Unexpected deploy counts in blocks'
