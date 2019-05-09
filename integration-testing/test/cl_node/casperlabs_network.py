import docker
import logging

from typing import List, Callable


from test.cl_node.docker_base import DockerConfig
from test.cl_node.casperlabs_node import CasperLabsNode
from test.cl_node.common import random_string
from test.cl_node.pregenerated_keypairs import PREGENERATED_KEYPAIRS
from test.cl_node.wait import (
    wait_for_approved_block_received_handler_state,
    wait_for_node_started,
)


class CasperLabsNetwork:
    """
    CasperLabsNetwork is the base object for a network of 0-many CasperLabNodes.

    A subclass should implement `_create_cl_network` to stand up the type of network it needs.

    Convention is naming the bootstrap as number 0 and all others increment from that point.
    """

    def __init__(self, docker_client: 'DockerClient'):
        self._next_key_number = 0
        self.docker_client = docker_client
        self.cl_nodes: List[CasperLabsNode] = []
        self._created_networks: List[str] = []

    @property
    def node_count(self):
        return len(self.cl_nodes)

    @property
    def docker_nodes(self):
        return [cl_node.node for cl_node in self.cl_nodes]

    def get_key(self):
        key_pair = PREGENERATED_KEYPAIRS[self._next_key_number]
        self._next_key_number += 1
        return key_pair

    def create_cl_network(self):
        """
        Should be implemented with each network class to setup custom nodes and networks.
        """
        raise NotImplementedError("Must implement '_create_network' in subclass.")

    def create_docker_network(self) -> str:
        network_name = f'casperlabs{random_string(5)}'
        self._created_networks.append(network_name)
        self.docker_client.networks.create(network_name, driver="bridge")
        logging.info(f'Docker network {network_name} created.')
        return network_name

    def _add_cl_node(self, config: DockerConfig) -> None:
        config.number = self.node_count
        cl_node = CasperLabsNode(config)
        self.cl_nodes.append(cl_node)

    def add_bootstrap(self, config: DockerConfig) -> None:
        if self.node_count > 0:
            raise Exception('There can be only one bootstrap')
        config.is_bootstrap = True
        self._add_cl_node(config)
        self.wait_method(wait_for_node_started, 0)

    def add_cl_node(self, config: DockerConfig, network_with_bootstrap: bool=True) -> None:
        if self.node_count == 0:
            raise Exception('Must create bootstrap first')
        config.bootstrap_address = self.cl_nodes[0].node.address
        if network_with_bootstrap:
            config.network = self.cl_nodes[0].node.network
        self._add_cl_node(config)

    def wait_method(self, method: Callable, node_number: int) -> None:
        """
        Calls a wait method with the node and timeout from internal objects

        Blocks until satisfied or timeout.

        :param method: wait method to call
        :param node_number: index of self.cl_nodes to use as node
        :return: None
        """
        cl_node = self.cl_nodes[node_number]
        method(cl_node.node, cl_node.config.command_timeout)

    def __enter__(self):
        return self

    def __exit__(self, exception_type, exception_value, traceback=None):
        if exception_type is not None:
            logging.error(f'Python Exception Occurred: {exception_type}')
            logging.error(exception_value)
        for node in self.cl_nodes:
            node.cleanup()
        self.cleanup()
        return True

    def cleanup(self):
        for network_name in self._created_networks:
            self.docker_client.networks.get(network_name).remove()


class OneNodeNetwork(CasperLabsNetwork):
    """ A single node network with just a bootstrap """

    def create_cl_network(self):
        kp = self.get_key()
        config = DockerConfig(self.docker_client,
                              node_private_key=kp.private_key,
                              node_public_key=kp.public_key,
                              network=self.create_docker_network())
        self.add_bootstrap(config)


class TwoNodeNetwork(CasperLabsNetwork):
    """ Two networked nodes """

    def create_cl_network(self):
        kp = self.get_key()
        config = DockerConfig(self.docker_client,
                              node_private_key=kp.private_key,
                              node_public_key=kp.public_key,
                              network=self.create_docker_network())
        self.add_bootstrap(config)

        kp = self.get_key()
        config = DockerConfig(self.docker_client, node_private_key=kp.private_key)
        self.add_cl_node(config)
        self.wait_method(wait_for_approved_block_received_handler_state, 1)


# class ThreeNodeNetwork(CasperLabsNetwork):
#     """ Three node network plus bootstrap """
#
#     def create_cl_network(self):
#         # TODO: Add proper wait states for detecting network is fully up
#         is_bootstrap = True
#         network = self.create_docker_network()
#         for node_number in range(4):
#             kp = self.get_key()
#             config = DockerConfig(docker_client=self.docker_client, number=node_number, is_bootstrap=is_bootstrap,
#                                   node_private_key=kp.private_key, node_public_key=kp.public_key, network=network)
#             self.add_cl_node(config)
#             is_bootstrap = False


if __name__ == '__main__':
    # For testing adding new networks.

    import logging
    import sys
    import time

    root = logging.getLogger()
    root.setLevel(logging.DEBUG)

    handler = logging.StreamHandler(sys.stdout)
    handler.setLevel(logging.DEBUG)
    formatter = logging.Formatter('%(asctime)s - %(name)s - %(levelname)s - %(message)s')
    handler.setFormatter(formatter)
    root.addHandler(handler)

    # Just testing standing them up.
    # with OneNodeNetwork(docker.from_env()) as onn:
    #     pass

    with TwoNodeNetwork(docker.from_env()) as net:
        net.create_cl_network()
        time.sleep(10)
