import logging
from collections import defaultdict
from test.cl_node.casperlabsnode import extract_block_count_from_show_blocks
from test.cl_node.client_base import CasperLabsClient
from test.cl_node.common import random_string
from test.cl_node.errors import NonZeroExitCodeError
from typing import Optional

from docker.errors import ContainerError


class DockerClient(CasperLabsClient):

    def __init__(self, node: 'DockerNode'):
        self.node = node
        self.docker_client = node.config.docker_client
        self.nonce = defaultdict(int)

    @property
    def client_type(self) -> str:
        return 'docker'

    def invoke_client(self, command: str) -> str:
        volumes = {
            self.node.host_mount_dir: {
                'bind': '/data',
                'mode': 'ro'
            }
        }
        try:
            command = f'--host {self.node.container_name} {command}'
            logging.info(f"COMMAND {command}")
            output = self.docker_client.containers.run(
                image=f"casperlabs/client:{self.node.docker_tag}",
                auto_remove=True,
                name=f"client-{self.node.config.number}-{random_string(5)}",
                command=command,
                network=self.node.network,
                volumes=volumes,
            ).decode('utf-8')
            logging.debug(f"OUTPUT {self.node.container_name} {output}")
            return output
        except ContainerError as err:
            logging.warning(f"EXITED code={err.exit_status} command='{err.command}' stderr='{err.stderr}'")
            raise NonZeroExitCodeError(command=(command, err.exit_status), exit_code=err.exit_status, output=err.stderr)

    def propose(self) -> str:
        return self.invoke_client('propose')

    def deploy(self,
               from_address: str = "00000000000000000000000000000000",
               gas_limit: int = 1000000,
               gas_price: int = 1,
               nonce: int = None,
               session_contract: Optional[str] = 'test_helloname.wasm',
               payment_contract: Optional[str] = 'test_helloname.wasm',
               private_key: Optional[str] = None,
               public_key: Optional[str] = None) -> str:

        deploy_nonce = nonce if nonce is not None else self.nonce[from_address]
        command = (f"deploy --from {from_address}"
                   f" --gas-limit {gas_limit} --gas-price {gas_price}"
                   f" --nonce {deploy_nonce} --session=/data/{session_contract}"
                   f" --payment=/data/{payment_contract}")

        if public_key and private_key:
            command += (f" --private-key=/data/{private_key}"
                        f" --public-key=/data/{public_key}")

        r = self.invoke_client(command)
        if 'Success' in r and nonce is None:
            self.nonce[from_address] += 1
        return r
        

    def show_block(self, block_hash: str) -> str:
        return self.invoke_client(f'show-block {block_hash}')

    def show_blocks(self, depth: int) -> str:
        return self.invoke_client(f"show-blocks --depth={depth}")

    def get_blocks_count(self, depth: int) -> int:
        show_blocks_output = self.show_blocks(depth)
        return extract_block_count_from_show_blocks(show_blocks_output)

    def vdag(self, depth: int, show_justification_lines: bool = False) -> str:
        just_text = '--show-justification-lines' if show_justification_lines else ''
        return self.invoke_client(f'vdag --depth {depth} {just_text}')
