from typing import Optional
import os
import logging

from test.cl_node.client_base import CasperLabsClient
from test.cl_node.nonce_registry import NonceRegistry
from casper_client import CasperClient, ABI


class PythonClient(CasperLabsClient):

    def __init__(self, node: 'DockerNode'):
        self.node = node
        self.abi = ABI
        # If $TAG_NAME is set it means we are running in docker, see docker_run_test.sh
        host = os.environ.get('TAG_NAME', None) and self.node.container_name or 'localhost'

        self.client = CasperClient(host=host,
                                   internal_port=self.node.grpc_internal_docker_port,
                                   port=self.node.grpc_external_docker_port)
        logging.info(f'PythonClient(host={self.client.host}, '
                     f'port={self.node.grpc_external_docker_port}, '
                     f'internal_port={self.node.grpc_internal_docker_port})')

    @property
    def client_type(self) -> str:
        return 'python'

    def deploy(self,
               from_address: str = None,
               gas_limit: int = 1000000,
               gas_price: int = 1,
               nonce: int = None,
               session_contract: Optional[str] = None,
               payment_contract: Optional[str] = None,
               private_key: Optional[str] = None,
               public_key: Optional[str] = None,
               args: list = None) -> str:

        assert session_contract is not None
        assert payment_contract is not None

        address = from_address or self.node.from_address
        deploy_nonce = nonce if nonce is not None else NonceRegistry.next(address)

        resources_path = self.node.resources_folder
        session_contract_path = str(resources_path / session_contract)
        payment_contract_path = str(resources_path / payment_contract)

        logging.info(f'PY_CLIENT.deploy(from_address={address}, gas_limit={gas_limit}, gas_price={gas_price}, '
                     f'payment_contract={payment_contract_path}, session_contract={session_contract_path}, '
                     f'nonce={deploy_nonce})')

        try:
            r = self.client.deploy(address.encode('UTF-8'),
                                   gas_limit,
                                   gas_price,
                                   payment_contract_path,
                                   session_contract_path,
                                   deploy_nonce,
                                   public_key,
                                   private_key,
                                   args)
            return r
        except:
            if nonce is None:
                NonceRegistry.revert(address)
            raise

    def propose(self) -> str:
        logging.info(f'PY_CLIENT.propose() for {self.client.host}')
        return self.client.propose()

    def query_state(self, block_hash: str, key: str, path: str, key_type: str):
        return self.client.queryState(block_hash, key, path, key_type)

    def show_block(self, block_hash: str):
        return self.client.showBlock(block_hash)

    def show_blocks(self, depth: int):
        return self.client.showBlocks(depth)

    def get_blocks_count(self, depth: int) -> int:
        return len(list(self.show_blocks(depth)))

    def show_deploys(self, block_hash: str):
        return self.client.showDeploys(block_hash)

    def show_deploy(self, deploy_hash: str):
        return self.client.showDeploy(deploy_hash)
