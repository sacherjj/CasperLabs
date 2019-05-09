from threading import Thread
from time import time, sleep
import logging

CONTRACT_1 = 'helloname_invalid_just_1.wasm'
CONTRACT_2 = 'helloname_invalid_just_2.wasm'


class TimedDeployProposeThread(Thread):

    def __init__(self,
                 docker_node: 'DockerNode',
                 deploy_kwargs: dict,
                 start_time: float) -> None:
        Thread.__init__(self)
        self.name = docker_node.name
        self.node = docker_node
        self.deploy_kwargs = deploy_kwargs
        self.start_time = start_time

    def run(self) -> None:
        if self.start_time <= time():
            raise Exception(f'start_time: {self.start_time} is past current time: {time()}')

        while self.start_time > time():
            sleep(0.001)
        self.node.deploy(**self.deploy_kwargs)
        self.node.propose()


def test_neglected_invalid_justification(three_node_network):
    bootstrap, node1, node2 = three_node_network.docker_nodes
    for cycle_count in range(5):
        logging.info(f'DEPLOY_PROPOSE CYCLE COUNT: {cycle_count + 1}')
        start_time = time() + 2

        boot_deploy = TimedDeployProposeThread(bootstrap, {'session_contract': CONTRACT_1}, start_time)
        node1_deploy = TimedDeployProposeThread(node1, {'session_contract': CONTRACT_2}, start_time)
        node2_deploy = TimedDeployProposeThread(node2, {'session_contract': CONTRACT_2}, start_time)

        node1_deploy.start()
        boot_deploy.start()
        node2_deploy.start()

        boot_deploy.join()
        node1_deploy.join()
        node2_deploy.join()

        sleep(1)


    # assert 'Success!' in bootstrap.deploy(session_contract=CONTRACT_1)
    #     assert 'Success!' in node1.deploy(session_contract=CONTRACT_2)
    #     assert 'Success!' in node2.deploy(session_contract=CONTRACT_2)
    #     assert 'Success!' in bootstrap.propose()
    #     assert 'Success!' in node1.propose()
    #     assert 'Success!' in node2.propose()
    # b_vdag = bootstrap.vdag(10)
    # n_vdag = node1.vdag(10)
    # b_blocks = bootstrap.blocks_as_list_with_depth(10)
    # n_blocks = node1.blocks_as_list_with_depth(10)
    # b_tuple_space_hashes = [b['tupleSpaceHash'] for b in b_blocks]
    # n_tuple_space_hashes = [b['tupleSpaceHash'] for b in n_blocks]
    # assert b_vdag == n_vdag


# Invalid blocks in errors

