import os
import logging
from pathlib import Path
import shutil
import tempfile
from typing import TYPE_CHECKING, Tuple, Generator
import re

from test.cl_node.errors import (
    CasperLabsNodeAddressNotFoundError,
)

from test.cl_node.docker_base import LoggingDockerBase

if TYPE_CHECKING:
    from test.cl_node.docker_base import DockerConfig

# TODO: Either load casper_client as a package, or fix import
# Copied casper_client into directory structure for now.
from test.cl_node.client.casper_client import CasperClient

# def get_python_client_folder() -> Path:
#     """ This will return the resources folder that is copied into the correct location for testing """
#     cur_path = Path(os.path.realpath(__file__)).parent
#     while cur_path.name != 'CasperLabs':
#         cur_path = cur_path.parent
#     return cur_path / 'client' / 'python' / 'src'
#
#
#
# sys.path.append(get_python_client_folder())
#


def get_resources_folder() -> Path:
    """ This will return the resources folder that is copied into the correct location for testing """
    cur_path = Path(os.path.realpath(__file__)).parent
    while cur_path.name != 'integration-testing':
        cur_path = cur_path.parent
    return cur_path / 'resources'


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

    NETWORK_PORT = '40400'
    GRPC_PORT = '40401'
    HTTP_PORT = '40403'
    KADEMLIA_PORT = '40404'

    @property
    def container_type(self):
        return 'node'

    def _get_container(self):
        env = {
            'RUST_BACKTRACE': 'full'
        }
        java_options = os.environ.get('_JAVA_OPTIONS')
        if java_options is not None:
            env['_JAVA_OPTIONS'] = java_options

        self.deploy_dir = tempfile.mkdtemp(dir="/tmp", prefix='deploy_')
        self.create_resources_dir()

        container = self.config.docker_client.containers.run(
            self.image_name,
            name=self.container_name,
            user='root',
            auto_remove=False,
            detach=True,
            mem_limit=self.config.mem_limit,
            ports={f'{self.GRPC_PORT}/tcp': self.config.grpc_port},  # Exposing grpc for Python Client
            network=self.config.network,
            volumes=self.volumes,
            command=self.container_command,
            hostname=self.container_name,
            environment=env,
        )
        self.client = CasperClient(port=self.config.grpc_port)
        return container

    def create_resources_dir(self) -> None:
        if os.path.exists(self.host_mount_dir):
            shutil.rmtree(self.host_mount_dir)
        resources_source_path = get_resources_folder()
        shutil.copytree(resources_source_path, self.host_mount_dir)

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

    def deploy(self, from_address: str = "00000000000000000000",
               gas_limit: int = 1000000, gas_price: int = 1, nonce: int = 0,
               session_contract_path=None,
               payment_contract_path=None) -> str:

        if session_contract_path is None:
            session_contract_path = f'{self.host_mount_dir}/helloname.wasm'
        if payment_contract_path is None:
            payment_contract_path = f'{self.host_mount_dir}/helloname.wasm'

        logging.info(f'PY_CLIENT.deploy(from_address={from_address}, gas_limit={gas_limit}, gas_price={gas_price}, '
                     f'payment_contract={payment_contract_path}, session_contract={session_contract_path}, '
                     f'nonce={nonce})')
        return self.client.deploy(from_address.encode('UTF-8'), gas_limit, gas_price,
                                  payment_contract_path, session_contract_path, nonce)

    def propose(self):
        logging.info(f'PY_CLIENT.propose()')
        return self.client.propose()

    def show_blocks_with_depth(self, depth: int) -> Generator:
        return self.client.showBlocks(depth)

    def get_blocks_count(self, depth: int) -> int:
        block_count = sum([1 for _ in self.show_blocks_with_depth(depth)])
        return block_count

    @property
    def address(self) -> str:
        m = re.search(f"Listening for traffic on (casperlabs://.+@{self.container.name}\\?protocol=\\d+&discovery=\\d+)\\.$",
                      self.logs,
                      re.MULTILINE | re.DOTALL)
        if m is None:
            raise CasperLabsNodeAddressNotFoundError()
        address = m.group(1)
        return address
