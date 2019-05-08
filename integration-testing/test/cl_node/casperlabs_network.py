import docker
import logging

from typing import List


from test.cl_node.docker_base import DockerConfig
from test.cl_node.casperlabs_node import CasperLabsNode
from test.cl_node.common import random_string
from test.cl_node.pregenerated_keypairs import PREGENERATED_KEYPAIRS
from test.cl_node.wait import (
    wait_for_approved_block_received_handler_state,
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
        self.clnodes: List[CasperLabsNode] = []
        self._created_networks: List[str] = []
        self._create_cl_network()

    def get_key(self):
        key_pair = PREGENERATED_KEYPAIRS[self._next_key_number]
        self._next_key_number += 1
        return key_pair

    def _create_cl_network(self):
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

    def add_clnode(self, config: DockerConfig):
        config.number = len(self.clnodes)
        cl_node = CasperLabsNode(config)
        self.clnodes.append(cl_node)

    def __enter__(self):
        return self

    def __exit__(self, exception_type, exception_value, traceback=None):
        if exception_type is not None:
            logging.error(f'Python Exception Occurred: {exception_type}')
            logging.error(exception_value)
        for node in self.clnodes:
            node.cleanup()
        self.cleanup()

    def cleanup(self):
        for network_name in self._created_networks:
            self.docker_client.networks.get(network_name).remove()


class OneNodeNetwork(CasperLabsNetwork):
    """ A single node network with just a bootstrap """

    def _create_cl_network(self):
        kp = self.get_key()
        config = DockerConfig(docker_client=self.docker_client, number=0, is_bootstrap=True,
                              node_private_key=kp.private_key, node_public_key=kp.public_key)
        self.add_clnode(config)
        wait_for_approved_block_received_handler_state(self.clnodes[0].node, self.clnodes[0].config.command_timeout)


class ThreeNodeNetwork(CasperLabsNetwork):
    """ Three node network plus bootstrap """

    def _create_cl_network(self):
        # TODO: Add proper wait states for detecting network is fully up
        is_bootstrap = True
        network = self.create_docker_network()
        for node_number in range(4):
            kp = self.get_key()
            config = DockerConfig(docker_client=self.docker_client, number=node_number, is_bootstrap=is_bootstrap,
                                  node_private_key=kp.private_key, node_public_key=kp.public_key, network=network)
            self.add_clnode(config)
            is_bootstrap = False


if __name__ == '__main__':
    # For testing adding new networks.

    import logging
    import sys

    root = logging.getLogger()
    root.setLevel(logging.DEBUG)

    handler = logging.StreamHandler(sys.stdout)
    handler.setLevel(logging.DEBUG)
    formatter = logging.Formatter('%(asctime)s - %(name)s - %(levelname)s - %(message)s')
    handler.setFormatter(formatter)
    root.addHandler(handler)

    # Just testing standing them up.
    with OneNodeNetwork(docker.from_env()) as onn:
        pass
    with ThreeNodeNetwork(docker.from_env()) as tnn:
        pass
