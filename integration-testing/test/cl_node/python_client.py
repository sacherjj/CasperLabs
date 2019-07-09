from typing import Optional
import logging

from test.cl_node.client_base import CasperLabsClient
from test.cl_node.nonce_registry import NonceRegistry
from casper_client import CasperClient


class PythonClient(CasperLabsClient):

    def __init__(self, node: 'DockerNode'):
        self.node = node
        self.client = CasperClient(host=self.node.container_name,
                                   internal_port=self.node.grpc_internal_docker_port,
                                   port=self.node.grpc_external_docker_port)
        logging.info(f'PythonClient(host={self.node.container_name}, '
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
               session_contract: Optional[str] = 'test_helloname.wasm',
               payment_contract: Optional[str] = 'test_helloname.wasm') -> str:

        address = from_address or self.node.from_address
        deploy_nonce = nonce if nonce is not None else NonceRegistry.next(address)

        resources_path = self.node.resources_folder
        session_contract_path = str(resources_path / session_contract)
        payment_contract_path = str(resources_path / payment_contract)

        logging.info(f'PY_CLIENT.deploy(from_address={address}, gas_limit={gas_limit}, gas_price={gas_price}, '
                     f'payment_contract={payment_contract_path}, session_contract={session_contract_path}, '
                     f'nonce={deploy_nonce})')

        r = self.client.deploy(address.encode('UTF-8'), gas_limit, gas_price, payment_contract, session_contract, deploy_nonce)
        return r

    def propose(self) -> str:
        logging.info(f'PY_CLIENT.propose() for {self.node.container_name}')
        return self.client.propose()

    def query_state(self, block_hash: str, key: str, path: str, key_type: str):
        return self.client.queryState(block_hash, key, path, key_type)

    def show_block(self, block_hash: str) -> str:
        # TODO:
        raise Exception("Not implemented yet")

    def show_blocks(self, depth: int):
        return self.client.showBlocks(depth)

    def get_blocks_count(self, depth: int) -> int:
        return len(list(self.show_blocks(depth)))

    def show_deploys(self, hash: str):
        raise Exception("Not implemented yet")

    def show_deploy(self, hash: str):
        raise Exception("Not implemented yet")
