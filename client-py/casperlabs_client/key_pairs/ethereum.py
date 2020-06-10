from typing import Union
from pathlib import Path

import ecdsa
from casperlabs_client.consts import SECP256K1_ETHEREUM_KEY_ALGORITHM
from casperlabs_client.io import read_binary_file
from .key_pair import KeyPair


class EthereumKey(KeyPair):
    """ Class for loading, generating and handling public/private key pairs using secp256k1 algorithm """

    def __init__(
        self,
        private_key_pem: bytes = None,
        private_key=None,
        public_key_pem: bytes = None,
        public_key=None,
    ):
        super().__init__(
            private_key_pem,
            private_key,
            public_key_pem,
            public_key,
            SECP256K1_ETHEREUM_KEY_ALGORITHM,
        )

    @property
    def private_key_pem(self):
        if self._private_key_pem is None:
            if self._private_key is None:
                raise ValueError("Must have either _private_key or _private_key_pem.")
            private_key_object = ecdsa.SigningKey.from_string(
                self._private_key
            )  # is from_bytes
            self._private_key_pem = private_key_object.to_pem()
        return self._private_key_pem

    @property
    def private_key(self):
        if self._private_key is None:
            if self._private_key_pem is None:
                raise ValueError("Must have either _private_key or _private_key_pem.")
            private_key_object = ecdsa.SigningKey.from_pem(self._private_key_pem)
            self._private_key = private_key_object.to_string()  # is to_bytes
        return self._private_key

    @property
    def public_key_pem(self):
        if self._public_key_pem is None:
            public_key_object = ecdsa.VerifyingKey.from_string(
                self.public_key
            )  # is from_bytes
            self._public_key_pem = public_key_object.to_pem()
        return self._public_key_pem

    @property
    def public_key(self):
        if self._public_key is None:
            private_key_object = ecdsa.SigningKey.from_string(
                self.private_key
            )  # is from_bytes
            self._public_key = (
                private_key_object.verifying_key.to_string()
            )  # is to_bytes
        return self._public_key

    def sign(self, data: bytes) -> bytes:
        private_key_object = ecdsa.SigningKey.from_string(
            self.public_key
        )  # is from_bytes
        return private_key_object.sign(data)

    @staticmethod
    def generate():
        """
        Generates a new key pair and returns as EthereumKey object.

        :returns EthereumKey object
        """
        private_key_object = ecdsa.SigningKey.generate()
        private_key = private_key_object.to_string()  # to_bytes
        return EthereumKey(private_key=private_key)

    @staticmethod
    def from_private_key_path(private_key_pem_path: Union[str, Path]) -> "KeyPair":
        """ Creates EthereumKey object from private key file in pem format """
        private_key_pem = read_binary_file(private_key_pem_path)
        return EthereumKey(private_key_pem=private_key_pem)

    @staticmethod
    def from_private_key(private_key: bytes) -> "KeyPair":
        """ Creates EthereumKey object from private key in bytes """
        ecdsa.SigningKey.from_string(private_key)
        return EthereumKey(private_key=private_key)
