from dataclasses import dataclass
from pathlib import Path
import base64

from test.cl_node.pregenerated_keypairs import KeyPair


@dataclass
class ValidatorKey:
    """
    This handles validator keys from resources/bootstrap_certificate which are used for creating bonds.txt.
    """
    node_num: int
    file_path: str

    @property
    def public_key_file_path(self) -> str:
        return Path(self.file_path) / f'validator-{self.node_num}-id'

    @property
    def public_key(self) -> str:
        with open(self.public_key_file_path) as f:
            key = f.read()
        return key

    @property
    def public_certificate_path(self) -> str:
        return Path(self.file_path) / f'validator-{self.node_num}-public.pem'

    @property
    def private_certificate_path(self) -> str:
        return Path(self.file_path) / f'validator-{self.node_num}-private.pem'

    @property
    def private_key(self):
        return self.parse_certificate_file(self.private_certificate_path)

    @property
    def key_pair(self):
        return KeyPair(private_key=self.private_key, public_key=self.public_key)

    @staticmethod
    def parse_certificate_file(file_path: str) -> str:
        with open(file_path) as f:
            key_base64 = f.readlines()[1].strip()
        key_raw = base64.b64decode(key_base64)

        if len(key_raw) % 32 == 0:
            key_raw = key_raw[:32]
        else:
            key_raw = key_raw[-32:]
        return base64.encodebytes(key_raw).decode('UTF-8').strip()


if __name__ == '__main__':
    from test.cl_node.common import get_root_test_path
    # Validate certificate parsing by comparing to public_key file
    for i in range(1, 21):
        root = get_root_test_path()
        val = ValidatorKey(i, root / "resources" / "bootstrap_certificate")
        print(f'{val.public_key} == {val.parse_certificate_file(val.public_certificate_path)}')
        assert val.public_key == val.parse_certificate_file(val.public_certificate_path)
