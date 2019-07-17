from dataclasses import dataclass
from pathlib import Path


@dataclass
class NodeKey:
    node_num: int
    file_path: str

    @property
    def public_key_file_path(self) -> str:
        return Path(self.file_path) / f'node-{self.node_num}-id'

    @property
    def public_key(self) -> str:
        with open(self.public_key_file_path) as f:
            key = f.read()
        return key

    @property
    def public_certificate_path(self) -> str:
        return Path(self.file_path) / f'node-{self.node_num}.certificate.pem'

    @property
    def private_certificate_path(self) -> str:
        return Path(self.file_path) / f'node-{self.node_num}.key.pem'
