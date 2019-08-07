from abc import ABC, abstractmethod
from typing import Optional


class CasperLabsClient(ABC):
    @property
    @abstractmethod
    def client_type(self) -> str:
        return "abstract"

    @abstractmethod
    def deploy(
        self,
        from_address: str = None,
        gas_limit: int = 1000000,
        gas_price: int = 1,
        nonce: int = None,  # nonce == None means framework should provide correct nonce
        session_contract: Optional[str] = None,
        payment_contract: Optional[str] = None,
    ) -> str:
        pass

    @abstractmethod
    def propose(self) -> str:
        pass

    @abstractmethod
    def show_block(self, block_hash: str) -> str:
        pass

    @abstractmethod
    def show_blocks(self, depth: int):
        pass

    @abstractmethod
    def query_state(self, block_hash: str, key: str, path: str, key_type: str):
        pass

    @abstractmethod
    def show_deploys(self, hash: str):
        pass

    @abstractmethod
    def show_deploy(self, hash: str):
        pass
