from threading import Thread
from time import time, sleep
import logging

CONTRACT_1 = 'helloname_invalid_just_1.wasm'
CONTRACT_2 = 'helloname_invalid_just_2.wasm'


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
        self.node.client.propose()


def test_neglected_invalid_block(three_node_network):
    """
    Feature file: neglected_invalid_justification.feature
    Scenario: 3 Nodes doing simultaneous deploys and proposes do not have neglected invalid blocks
    """
    bootstrap, node1, node2 = three_node_network.docker_nodes
    for cycle_count in range(4):
        logging.info(f'DEPLOY_PROPOSE CYCLE COUNT: {cycle_count + 1}')
        start_time = time() + 1

        boot_deploy = DeployTimedTread(bootstrap, {'session_contract': CONTRACT_1}, start_time)
        node1_deploy = DeployTimedTread(node1, {'session_contract': CONTRACT_2}, start_time)
        node2_deploy = DeployTimedTread(node2, {'session_contract': CONTRACT_2}, start_time)

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

    sleep(5)

    assert ' for NeglectedInvalidBlock.' not in bootstrap.logs()
    assert ' for NeglectedInvalidBlock.' not in node1.logs()
    assert ' for NeglectedInvalidBlock.' not in node2.logs()
