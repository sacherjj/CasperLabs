from abc import ABC, abstractmethod
from pathlib import Path
from typing import Optional, Union


class CasperLabsClientBase(ABC):
    @property
    @abstractmethod
    def client_type(self) -> str:
        return "abstract"

    @abstractmethod
    def deploy(
        self,
        from_address: str = None,
        gas_price: int = 1,
        session_contract: Optional[str] = None,
        session_args: Optional[Union[str, bytes]] = None,
        payment_contract: Optional[str] = None,
        payment_args: Optional[Union[str, bytes]] = None,
        public_key: Optional[Union[str, Path]] = None,
        private_key: Optional[Union[str, Path]] = None,
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
