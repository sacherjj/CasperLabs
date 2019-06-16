import logging
import time
from typing import Optional
from collections import defaultdict

from test.cl_node.casperlabsnode import extract_block_count_from_show_blocks
from test.cl_node.client_base import CasperLabsClient
from test.cl_node.common import random_string
from test.cl_node.errors import NonZeroExitCodeError
from test.cl_node.client_parser import parse
import docker.errors
from test.cl_node.nonce_registry import NonceRegistry

from docker.errors import ContainerError


class DockerClient(CasperLabsClient):

    def __init__(self, node: 'DockerNode'):
        self.node = node
        self.docker_client = node.config.docker_client

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
        command = f'--host {self.node.container_name} {command}'
        logging.info(f"COMMAND {command}")
        container = self.docker_client.containers.run(
            image = f"casperlabs/client:{self.node.docker_tag}",
            name = f"client-{self.node.config.number}-{random_string(5)}",
            command = command,
            network = self.node.network,
            volumes = volumes,
            detach = True,
            stderr = True,
            stdout = True,
        )
        r = container.wait()
        error, status_code = r['Error'], r['StatusCode']
        stdout = container.logs(stdout=True, stderr=False).decode('utf-8')
        stderr = container.logs(stdout=False, stderr=True).decode('utf-8')
        logging.info(f"EXITED exit_code: {status_code} STDERR: {stderr} STDOUT: {stdout}")

        try:
            container.remove()
        except docker.errors.APIError as e:
            logging.warning(f"Exception while removing docker client container: {str(e)}")

        if status_code: 
            raise NonZeroExitCodeError(command=(command, status_code), exit_code=status_code, output=stderr)

        return stdout

    def propose(self) -> str:
        return self.invoke_client('propose')

    def propose_with_retry(self, max_attempts: int, retry_seconds: int) -> str:
        # With many threads using the same account the nonces will be interleaved.
        # Only one node can propose at a time, the others have to wait until they
        # receive the block and then try proposing again.
        attempt = 0
        while True:
            try:
                return self.propose()
            except NonZeroExitCodeError:
                if attempt < max_attempts:
                    logging.debug("Could not propose; retrying later.")
                    attempt += 1
                    time.sleep(retry_seconds)
                else:
                    logging.debug("Could not propose; no more retries!")
                    raise ex

    def deploy(self,
               from_address: str = "3030303030303030303030303030303030303030303030303030303030303030",
               gas_limit: int = 1000000,
               gas_price: int = 1,
               nonce: Optional[int] = None,
               session_contract: str = 'test_helloname.wasm',
               payment_contract: str = 'test_helloname.wasm',
               private_key: Optional[str] = None,
               public_key: Optional[str] = None) -> str:

        deploy_nonce = nonce if nonce is not None else NonceRegistry.next(from_address)

        command = (f"deploy --from {from_address}"
                   f" --gas-limit {gas_limit}"
                   f" --gas-price {gas_price}"
                   f" --session=/data/{session_contract}"
                   f" --payment=/data/{payment_contract}")

        # For testing CLI: option will not be passed to CLI if nonce is ''
        if deploy_nonce != '':
            command += f" --nonce {deploy_nonce}"

        if public_key and private_key:
            command += (f" --private-key=/data/{private_key}"
                        f" --public-key=/data/{public_key}")

        r = self.invoke_client(command)
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

    def queryState(self, blockHash: str, key: str, path: str, keyType: str):
        """
        Subcommand: query-state - Query a value in the global state.
          -b, --block-hash  <arg>   Hash of the block to query the state of
          -k, --key  <arg>          Base16 encoding of the base key.
          -p, --path  <arg>         Path to the value to query. Must be of the form
                                    'key1/key2/.../keyn'
          -t, --type  <arg>         Type of base key. Must be one of 'hash', 'uref',
                                    'address'
          -h, --help                Show help message

        """
        return self.invoke_client(f'query-state '
                                  f' --block-hash "{blockHash}"'
                                  f' --key "{key}"'
                                  f' --path "{path}"'
                                  f' --type "{keyType}"')


    def show_deploys(self, hash: str):
        return parse(self.invoke_client(f'show-deploys {hash}'))


    def show_deploy(self, hash: str):
        return parse(self.invoke_client(f'show-deploy {hash}'))

