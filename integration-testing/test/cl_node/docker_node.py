import logging
import os
import re
import shutil
import tempfile
from pathlib import Path
from typing import List, Tuple, Dict

from test.cl_node.casperlabsnode import extract_block_hash_from_propose_output
from test.cl_node.docker_base import LoggingDockerBase
from test.cl_node.docker_client import DockerClient
from test.cl_node.errors import CasperLabsNodeAddressNotFoundError
from test.cl_node.pregenerated_keypairs import PREGENERATED_KEYPAIRS
from test.cl_node.python_client import PythonClient
from test.cl_node.docker_base import DockerConfig


class DockerNode(LoggingDockerBase):
    """
    A CasperLabs Docker Node object
    """
    CL_NODE_BINARY = '/opt/docker/bin/bootstrap'
    CL_NODE_DIRECTORY = "/root/.casperlabs"
    CL_NODE_DEPLOY_DIR = f"{CL_NODE_DIRECTORY}/deploy"
    CL_GENESIS_DIR = f'{CL_NODE_DIRECTORY}/genesis'
    CL_SOCKETS_DIR = f'{CL_NODE_DIRECTORY}/sockets'
    CL_BOOTSTRAP_DIR = f"{CL_NODE_DIRECTORY}/bootstrap"
    CL_BONDS_FILE = f"{CL_GENESIS_DIR}/bonds.txt"

    NETWORK_PORT = 40400
    GRPC_EXTERNAL_PORT = 40401
    GRPC_INTERNAL_PORT = 40402
    HTTP_PORT = 40403
    KADEMLIA_PORT = 40404

    DOCKER_CLIENT = 'd'
    PYTHON_CLIENT = 'p'

    def __init__(self, config: DockerConfig, socket_volume: str):
        super().__init__(config, socket_volume)
        self._client = self.DOCKER_CLIENT
        self.p_client = PythonClient(self)
        self.d_client = DockerClient(self)
        self.join_client_network()

    @property
    def docker_port_offset(self) -> int:
        if self.is_in_docker:
            return 0
        else:
            return self.number * 10

    @property
    def grpc_external_docker_port(self) -> int:
        return self.GRPC_EXTERNAL_PORT + self.docker_port_offset

    @property
    def grpc_internal_docker_port(self) -> int:
        return self.GRPC_INTERNAL_PORT + self.docker_port_offset

    @property
    def resources_folder(self) -> Path:
        """ This will return the resources folder that is copied into the correct location for testing """
        cur_path = Path(os.path.realpath(__file__)).parent
        while cur_path.name != 'integration-testing':
            cur_path = cur_path.parent
        return cur_path / 'resources'

    @property
    def timeout(self):
        """
        Number of seconds for a node to timeout.
        :return: int (seconds)
        """
        return self.config.command_timeout

    @property
    def number(self) -> int:
        return self.config.number

    @property
    def container_type(self):
        return 'node'

    @property
    def client(self) -> 'CasperLabsClient':
        return {self.DOCKER_CLIENT: self.d_client,
                self.PYTHON_CLIENT: self.p_client}[self._client]

    def use_python_client(self):
        self._client = self.PYTHON_CLIENT

    def use_docker_client(self):
        self._client = self.DOCKER_CLIENT

    @property
    def client_network_name(self) -> str:
        """
        This renders the network name that the docker client should have opened for Python Client connection
        """
        # Networks are created in docker_run_tests.sh.  Must stay in sync.
        return f'cl-{self.docker_tag}-{self.config.number}'

    def join_client_network(self) -> None:
        """
        Joins DockerNode to client network to enable Python Client communication
        """
        if os.environ.get('TAG_NAME'):
            # We are running in docker, because we have this environment variable
            self.connect_to_network(self.client_network_name)
            logging.info(f'Joining {self.container_name} to {self.client_network_name}.')

    @property
    def docker_ports(self) -> Dict[str, int]:
        """
        Generates a dictionary for docker port mapping.

        :return: dict for use in docker container run to open ports based on node number
        """
        return {f'{self.GRPC_INTERNAL_PORT}/tcp': self.grpc_internal_docker_port,
                f'{self.GRPC_EXTERNAL_PORT}/tcp': self.grpc_external_docker_port}

    def _get_container(self):
        env = {
            'RUST_BACKTRACE': 'full',
            'CL_LOG_LEVEL': os.environ.get('CL_LOG_LEVEL', 'INFO'),
            'CL_CASPER_IGNORE_DEPLOY_SIGNATURE': 'true',
            'CL_SERVER_NO_UPNP': 'true'
        }
        java_options = os.environ.get('_JAVA_OPTIONS')
        if java_options is not None:
            env['_JAVA_OPTIONS'] = java_options
        # Eliminate UPnP for docker, to not have delay
        if self.is_in_docker:
            env['CL_SERVER_NO_UPNP'] = 'true'
        self.deploy_dir = tempfile.mkdtemp(dir="/tmp", prefix='deploy_')
        self.create_resources_dir()

        commands = self.container_command
        logging.info(f'{self.container_name} commands: {commands}')

        # Locally, we use tcp ports.  In docker, we used docker networks
        ports = {}
        if not self.is_in_docker:
            ports = self.docker_ports

        container = self.config.docker_client.containers.run(
            self.image_name,
            name=self.container_name,
            user='root',
            auto_remove=False,
            detach=True,
            mem_limit=self.config.mem_limit,
            ports=ports,  # Exposing grpc for Python Client
            network=self.network,
            volumes=self.volumes,
            command=commands,
            hostname=self.container_name,
            environment=env,
        )
        # self.client = CasperClient(port=self.config.grpc_port)
        return container

    @property
    def network(self):
        return self.config.network

    def create_resources_dir(self) -> None:
        if os.path.exists(self.host_mount_dir):
            shutil.rmtree(self.host_mount_dir)
        shutil.copytree(str(self.resources_folder), self.host_mount_dir)
        self.create_bonds_file()

    def create_bonds_file(self) -> None:
        N = len(PREGENERATED_KEYPAIRS)
        path = f'{self.host_genesis_dir}/bonds.txt'
        os.makedirs(os.path.dirname(path))
        with open(path, 'a') as f:
            for i, pair in enumerate(PREGENERATED_KEYPAIRS):
                bond = N + 2 * i
                f.write(f'{pair.public_key} {bond}\n')

    def cleanup(self):
        super().cleanup()
        if os.path.exists(self.host_mount_dir):
            shutil.rmtree(self.host_mount_dir)
        if os.path.exists(self.deploy_dir):
            shutil.rmtree(self.deploy_dir)

    @property
    def volumes(self) -> dict:
        if self.config.volumes is not None:
            return self.config.volumes

        return {
                self.host_genesis_dir: {
                    "bind": self.CL_GENESIS_DIR,
                    "mode": "rw"
                },
                self.host_bootstrap_dir: {
                    "bind": self.CL_BOOTSTRAP_DIR,
                    "mode": "rw"
                },
                self.deploy_dir: {
                    "bind": self.CL_NODE_DEPLOY_DIR,
                    "mode": "rw"
                },
                self.socket_volume: {
                    "bind": self.CL_SOCKETS_DIR,
                    "mode": "rw"
                }
            }

    @property
    def container_command(self):
        bootstrap_flag = '-s' if self.config.is_bootstrap else ''
        options = [f'{opt} {arg}' for opt, arg in self.config.node_command_options(self.container_name).items()]
        return f"run {bootstrap_flag} {' '.join(options)}"

    def get_metrics(self) -> Tuple[int, str]:
        cmd = 'curl -s http://localhost:40403/metrics'
        output = self.exec_run(cmd=cmd)
        return output

    def get_metrics_strict(self):
        output = self.shell_out('curl', '-s', 'http://localhost:40403/metrics')
        return output

    def deploy_and_propose(self, **deploy_kwargs) -> str:
        deploy_output = self.client.deploy(**deploy_kwargs)
        assert 'Success!' in deploy_output
        block_hash_output_string = self.client.propose()
        block_hash = extract_block_hash_from_propose_output(block_hash_output_string)
        assert block_hash is not None
        logging.info(f"The block hash: {block_hash} generated for {self.container.name}")
        return block_hash

    def show_blocks(self) -> Tuple[int, str]:
        return self.exec_run(f'{self.CL_NODE_BINARY} show-blocks')

    def blocks_as_list_with_depth(self, depth: int) -> List:
        # TODO: Replace with generator using Python client
        result = self.client.show_blocks(depth)
        block_list = []
        for i, section in enumerate(result.split(' ---------------\n')):
            if i == 0:
                continue
            cur_block = {}
            for line in section.split('\n'):
                try:
                    name, value = line.split(': ', 1)
                    cur_block[name] = value.replace('"', '')
                except ValueError:
                    pass
            block_list.append(cur_block)
        block_list.reverse()
        return block_list

    @property
    def address(self) -> str:
        m = re.search(f"Listening for traffic on (casperlabs://.+@{self.container.name}\\?protocol=\\d+&discovery=\\d+)\\.$",
                      self.logs(),
                      re.MULTILINE | re.DOTALL)
        if m is None:
            raise CasperLabsNodeAddressNotFoundError()
        address = m.group(1)
        return address
