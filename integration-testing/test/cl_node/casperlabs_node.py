from test.cl_node.docker_node import DockerNode
from test.cl_node.docker_execution_engine import DockerExecutionEngine
from test.cl_node.common import random_string
import docker.errors


class CasperLabsNode:
    """
    CasperLabsNode is a DockerNode, DockerExecutionEngine
    DockerNode handles client calls

    """
    def __init__(self, config):
        self.config = config
        self.socket_volume = self.create_socket_volume()
        self.execution_engine = DockerExecutionEngine(config, socket_volume=self.socket_volume)
        self.node = DockerNode(config, socket_volume=self.socket_volume)
        self.name = f'cl_node-{self.config.number}'

    def create_socket_volume(self) -> str:
        volume_name = f"cl_socket_{random_string(5)}"
        self.config.docker_client.volumes.create(name=volume_name, driver="local")
        return volume_name

    def cleanup(self):
        self.node.cleanup()
        self.execution_engine.cleanup()
        try:
            self.config.docker_client.volumes.get(self.socket_volume).remove(force=True)
        except docker.errors.NotFound:
            pass
