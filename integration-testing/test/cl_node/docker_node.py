import os
import logging
from pathlib import Path
import shutil
import tempfile
from typing import TYPE_CHECKING, Tuple, Generator, Optional, List
import re

from docker.errors import ContainerError

from test.cl_node.errors import (
    CasperLabsNodeAddressNotFoundError,
    NonZeroExitCodeError,
)

from test.cl_node.docker_base import LoggingDockerBase
from test.cl_node.common import random_string
from test.cl_node.casperlabsnode import (
    extract_block_count_from_show_blocks,
    extract_block_hash_from_propose_output,
)
from test.cl_node.pregenerated_keypairs import PREGENERATED_KEYPAIRS

if TYPE_CHECKING:
    from test.cl_node.docker_base import DockerConfig

# TODO: Either load casper_client as a package, or fix import
# Copied casper_client into directory structure for now.
# from test.cl_node.client.casper_client import CasperClient

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

        commands = self.container_command
        logging.info(f'{self.container_name} commands: {commands}')

        container = self.config.docker_client.containers.run(
            self.image_name,
            name=self.container_name,
            user='root',
            auto_remove=False,
            detach=True,
            mem_limit=self.config.mem_limit,
            # If multiple tests are running in drone, local ports are duplicated.  Need a solution to this
            # Prior to implementing the python client.
            # ports={f'{self.GRPC_PORT}/tcp': self.config.grpc_port},  # Exposing grpc for Python Client
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
        resources_source_path = get_resources_folder()
        shutil.copytree(resources_source_path, self.host_mount_dir)
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

    def invoke_client(self, command: str) -> str:
        volumes = {
            self.host_mount_dir: {
                'bind': '/data',
                'mode': 'ro'
            }
        }
        try:
            command = f'--host {self.container_name} {command}'
            logging.info(f"COMMAND {command}")
            output = self.config.docker_client.containers.run(
                image=f"casperlabs/client:{self.docker_tag}",
                auto_remove=True,
                name=f"client-{self.config.number}-{random_string(5)}",
                command=command,
                network=self.network,
                volumes=volumes,
            ).decode('utf-8')
            logging.debug(f"OUTPUT {self.container_name} {output}")
            return output
        except ContainerError as err:
            logging.warning(f"EXITED code={err.exit_status} command='{err.command}' stderr='{err.stderr}'")
            raise NonZeroExitCodeError(command=(command, err.exit_status), exit_code=err.exit_status, output=err.stderr)

    def deploy(self,
               from_address: str = "00000000000000000000",
               gas_limit: int = 1000000,
               gas_price: int = 1,
               nonce: int = 0,
               session_contract: Optional[str]='helloname.wasm',
               payment_contract: Optional[str]='helloname.wasm') -> str:

        command = " ".join([
            "deploy",
            f"--from {from_address}",
            f"--gas-limit {gas_limit}",
            f"--gas-price {gas_price}",
            f"--nonce {nonce}",
            f"--session=/data/{session_contract}",
            f"--payment=/data/{payment_contract}"
        ])

        return self.invoke_client(command)

    def propose(self) -> str:
        return self.invoke_client('propose')

    def deploy_and_propose(self, **deploy_kwargs) -> str:
        deploy_output = self.deploy(**deploy_kwargs)
        assert 'Success!' in deploy_output
        block_hash_output_string = self.propose()
        block_hash = extract_block_hash_from_propose_output(block_hash_output_string)
        assert block_hash is not None
        logging.info(f"The block hash: {block_hash} generated for {self.container.name}")
        return block_hash

    def show_block(self, hash: str) -> str:
        return self.invoke_client(f'show-block {hash}')

    def show_blocks(self) -> Tuple[int, str]:
        return self.exec_run(f'{self.CL_NODE_BINARY} show-blocks')

    def show_blocks_with_depth(self, depth: int) -> str:
        return self.invoke_client(f"show-blocks --depth={depth}")

    def blocks_as_list_with_depth(self, depth: int) -> List:
        # TODO: Replace with generator using Python client
        result = self.show_blocks_with_depth(depth)
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

    def get_blocks_count(self, depth: int) -> int:
        show_blocks_output = self.show_blocks_with_depth(depth)
        return extract_block_count_from_show_blocks(show_blocks_output)

    def vdag(self, depth: int, show_justification_lines: bool = False) -> str:
        just_text = '--show-justification-lines' if show_justification_lines else ''
        return self.invoke_client(f'vdag --depth {depth} {just_text}')

    @property
    def address(self) -> str:
        m = re.search(f"Listening for traffic on (casperlabs://.+@{self.container.name}\\?protocol=\\d+&discovery=\\d+)\\.$",
                      self.logs(),
                      re.MULTILINE | re.DOTALL)
        if m is None:
            raise CasperLabsNodeAddressNotFoundError()
        address = m.group(1)
        return address

    # Methods for new Python Client that was temp removed.
    #
    # def deploy(self, from_address: str = "00000000000000000000",
    #            gas_limit: int = 1000000, gas_price: int = 1, nonce: int = 0,
    #            session_contract_path=None,
    #            payment_contract_path=None) -> str:
    #
    #     if session_contract_path is None:
    #         session_contract_path = f'{self.host_mount_dir}/helloname.wasm'
    #     if payment_contract_path is None:
    #         payment_contract_path = f'{self.host_mount_dir}/helloname.wasm'
    #
    #     logging.info(f'PY_CLIENT.deploy(from_address={from_address}, gas_limit={gas_limit}, gas_price={gas_price}, '
    #                  f'payment_contract={payment_contract_path}, session_contract={session_contract_path}, '
    #                  f'nonce={nonce})')
    #     return self.client.deploy(from_address.encode('UTF-8'), gas_limit, gas_price,
    #                               payment_contract_path, session_contract_path, nonce)
    #
    # def propose(self):
    #     logging.info(f'PY_CLIENT.propose()')
    #     return self.client.propose()
    #
    # def show_blocks_with_depth(self, depth: int) -> Generator:
    #     return self.client.showBlocks(depth)
    #
    # def get_blocks_count(self, depth: int) -> int:
    #     block_count = sum([1 for _ in self.show_blocks_with_depth(depth)])
    #     return block_count
