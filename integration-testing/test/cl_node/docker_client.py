import time
from typing import Optional
import docker.errors
import os


from test.cl_node import LoggingMixin
from test.cl_node.casperlabsnode import extract_block_count_from_show_blocks
from test.cl_node.casperlabsnode import extract_block_hash_from_propose_output
from test.cl_node.client_base import CasperLabsClient
from test.cl_node.common import random_string
from test.cl_node.errors import NonZeroExitCodeError
from test.cl_node.client_parser import parse, parse_show_deploys
from test.cl_node.nonce_registry import NonceRegistry
from test.cl_node.casperlabs_accounts import GENESIS_ACCOUNT
from pathlib import Path

def resource(file_name):
    RESOURCES_PATH="../../resources/"
    return Path(os.path.dirname(os.path.realpath(__file__)), RESOURCES_PATH, file_name)

class DockerClient(CasperLabsClient, LoggingMixin):

    def __init__(self, node: 'DockerNode'):
        self.node = node
        self.abi = None  # TODO: Translate Client ABI to similar to Python if implemented
        self.docker_client = node.config.docker_client
        super(DockerClient, self).__init__()

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
        self.logger.info(f"COMMAND {command}")
        container = self.docker_client.containers.run(
            image=f"casperlabs/client:{self.node.docker_tag}",
            name=f"client-{self.node.config.number}-{random_string(5)}",
            command=command,
            network=self.node.network,
            volumes=volumes,
            detach=True,
            stderr=True,
            stdout=True,
        )
        r = container.wait()
        error, status_code = r['Error'], r['StatusCode']
        stdout = container.logs(stdout=True, stderr=False).decode('utf-8')
        stderr = container.logs(stdout=False, stderr=True).decode('utf-8')

        # TODO: I don't understand why bug if I just call `self.logger.debug` then
        # it doesn't print anything, even though the level is clearly set.
        if self.log_level == 'DEBUG' or status_code != 0:
            self.logger.info(f"EXITED exit_code: {status_code} STDERR: {stderr} STDOUT: {stdout}")

        try:
            container.remove()
        except docker.errors.APIError as e:
            self.logger.warning(f"Exception while removing docker client container: {str(e)}")

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
                return extract_block_hash_from_propose_output(self.propose())
            except NonZeroExitCodeError as ex:
                if attempt < max_attempts:
                    self.logger.debug("Could not propose; retrying later.")
                    attempt += 1
                    time.sleep(retry_seconds)
                else:
                    self.logger.debug("Could not propose; no more retries!")
                    raise ex

    def get_balance(self, account_address: str, block_hash: str) -> int:
        """
        Returns balance of account according to block given.

        :param account_address: account public key in hex
        :param block_hash: block_hash in hex
        :return: balance as int
        """
        command = f"balance --address {account_address} --block-hash {block_hash}"
        r = self.invoke_client(command)
        try:
            balance = r.split(' : ')[1]
            return int(balance)
        except Exception as e:
            raise Exception(f'Error parsing: {r}.\n{e}')

    def deploy(self,
               from_address: str = None,
               gas_limit: int = 1000000,
               gas_price: int = 1,
               nonce: Optional[int] = None,
               session_contract: str = None,
               payment_contract: str = None,
               private_key: Optional[str] = None,
               public_key: Optional[str] = None) -> str:

        assert session_contract is not None
        assert payment_contract is not None

        address = from_address or GENESIS_ACCOUNT.public_key_hex

        # For now by default public_key and private_key will be keys of the genesis account.
        # This will probably change in the future, we may use accounts created specifically
        # for integration testing.

        def docker_path(p):
            return '/' + '/'.join(['data'] + str(p).split('/')[-2:])

        public_key = public_key or docker_path(GENESIS_ACCOUNT.public_key_path)
        private_key = private_key or docker_path(GENESIS_ACCOUNT.private_key_path)

        deploy_nonce = nonce if nonce is not None else NonceRegistry.next(address)
        payment_contract = payment_contract or session_contract

        command = (f"deploy --from {address}"
                   f" --gas-limit {gas_limit}"
                   f" --gas-price {gas_price}"
                   f" --session=/data/{session_contract}"
                   f" --payment=/data/{payment_contract}"
                   f" --private-key={private_key}"
                   f" --public-key={public_key}")

        # For testing CLI: option will not be passed to CLI if nonce is ''
        if deploy_nonce != '':
            command += f" --nonce {deploy_nonce}"

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

    def query_state(self, block_hash: str, key: str, path: str, key_type: str):
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
        return parse(self.invoke_client(f'query-state '
                                        f' --block-hash "{block_hash}"'
                                        f' --key "{key}"'
                                        f' --path "{path}"'
                                        f' --type "{key_type}"'))

    def show_deploys(self, hash: str):
        return parse_show_deploys(self.invoke_client(f'show-deploys {hash}'))

    def show_deploy(self, hash: str):
        return parse(self.invoke_client(f'show-deploy {hash}'))

    def query_purse_balance(self, block_hash: str, purse_id: str) -> Optional[float]:
        raise NotImplementedError()

