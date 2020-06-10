import base64
from dataclasses import dataclass
from abc import ABC, abstractmethod
from typing import Union
from pathlib import Path

import ecdsa
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ed25519


from . import crypto
from .consts import (
    ED25519_KEY_ALGORITHM,
    SECP256K1_ETHEREUM_KEY_ALGORITHM,
    SUPPORTED_KEY_ALGORITHMS,
)
from .io import read_binary_file, write_binary_file


@dataclass
class KeyPair(ABC):
    """ Abstract class for loading, generating and handling public/private key pairs """

    _private_key_pem: bytes = None
    _private_key = None
    _public_key_pem: bytes = None
    _public_key = None
    _algorithm: str = None

    @property
    def algorithm(self):
        """ String representation of the key algorithm """
        return self._algorithm

    @property
    @abstractmethod
    def private_key_pem(self):
        pass

    @property
    @abstractmethod
    def private_key(self):
        pass

    @property
    @abstractmethod
    def public_key_pem(self):
        pass

    @property
    @abstractmethod
    def public_key(self):
        pass

    def save_pem_files(
        self, save_directory: Union[str, Path], filename_prefix: str = "account-"
    ) -> None:
        """
        Save key pairs out as public and private pem files.

        :param save_directory:   str or Path to directory for saving pem files.
        :param filename_prefix:  prefix of filename to be used for save.
        """
        private_path = Path(save_directory) / f"{filename_prefix}private.pem"
        write_binary_file(private_path, self.private_key_pem)

        public_path = Path(save_directory) / f"{filename_prefix}public.pem"
        write_binary_file(public_path, self.public_key_pem)

    @staticmethod
    def class_from_algorithm(algorithm: str) -> "KeyPair":
        """ Get proper KeyPair class using algorithm string. """
        class_map = {
            ED25519_KEY_ALGORITHM: ED25519Key,
            SECP256K1_ETHEREUM_KEY_ALGORITHM: EthereumKey,
        }
        try:
            class_map[algorithm]
        except KeyError:
            ValueError(f"algorithm should be in ({SUPPORTED_KEY_ALGORITHMS})")

    @staticmethod
    @abstractmethod
    def from_private_key_path(private_key_pem_path: Union[str, Path]) -> "KeyPair":
        pass

    @abstractmethod
    def sign(self, data: bytes) -> bytes:
        pass

    @staticmethod
    def _parse_pem_data_line(pem_file_data: bytes):
        """
        :param pem_file_data: data from pem file as bytes
        :return: raw bytes from the data line
        """
        for line in pem_file_data.split(b"\n"):
            if not line.startswith(b"-----"):
                return line

    def account_hash(self) -> bytes:
        """ Generate hash of public key and key algorithm for use as primary identifier in the system """
        return crypto.blake2b_hash(
            self.algorithm.encode("UTF-8") + b"0x00" + self.public_key
        )


@dataclass
class ED25519Key(KeyPair):
    """ Class for loading, generating and handling public/private key pairs using ed25519 algorithm """

    def __post_init__(self):
        self._algorithm = ED25519_KEY_ALGORITHM

    @staticmethod
    def _parse_pem_data(pem_file_data: bytes):
        raw_data = KeyPair._parse_pem_data_line(pem_file_data)
        # TODO: Where does this magic come from?
        data = base64.b64decode(raw_data)
        if len(data) % 32 == 0:
            return data[:32]
        else:
            return data[-32:]

    @property
    def private_key_pem(self) -> bytes:
        """ Contents of private_key pem file. """
        if self._private_key_pem is None:
            if self._private_key is None:
                raise ValueError("Must have either _private_key or _private_key_pem.")
            private_key = ed25519.Ed25519PrivateKey.from_private_bytes(
                self._private_key
            )
            self._private_key_pem = private_key.private_bytes(
                encoding=serialization.Encoding.PEM,
                format=serialization.PrivateFormat.PKCS8,
                encryption_algorithm=serialization.NoEncryption(),
            )
        return self._private_key_pem

    @property
    def private_key(self) -> bytes:
        """ Private key as bytes """
        if self._private_key is None:
            if self._private_key_pem is None:
                raise ValueError("Must have either _private_key or _private_key_pem.")
            pem_data = self._parse_pem_data(self._private_key_pem)
            self._private_key = pem_data
        return self._private_key

    @property
    def public_key_pem(self) -> bytes:
        """ Contents of public_key pem file. """
        if self._public_key_pem is None:
            public_key = ed25519.Ed25519PublicKey.from_public_bytes(self.public_key)
            self._public_key_pem = public_key.public_bytes(
                encoding=serialization.Encoding.PEM,
                format=serialization.PublicFormat.SubjectPublicKeyInfo,
            )
        return self._public_key_pem

    @property
    def public_key(self) -> bytes:
        """ Public key as bytes """
        if self._public_key is None:
            private_key = ed25519.Ed25519PrivateKey.from_private_bytes(self.private_key)
            public_key = private_key.public_key()
            self._public_key = public_key.public_bytes(
                encoding=serialization.Encoding.Raw,
                format=serialization.PublicFormat.Raw,
            )
        return self._public_key

    def sign(self, data: bytes) -> bytes:
        return ed25519.Ed25519PrivateKey.from_private_bytes(self.private_key).sign(data)

    @staticmethod
    def from_private_key_path(private_key_pem_path: Union[str, Path]) -> "KeyPair":
        """ Returns a ED25519Key object loaded from a private_key_pem file"""
        private_pem = read_binary_file(private_key_pem_path)
        return ED25519Key(_private_key_pem=private_pem)

    @staticmethod
    def generate():
        """
        Generates a new key pair and returns as ED25519 object.

        :returns ED25519 object
        """
        private_key = ed25519.Ed25519PrivateKey.generate()

        private_pem = private_key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        )

        private_bytes = private_key.private_bytes(
            encoding=serialization.Encoding.Raw,
            format=serialization.PrivateFormat.Raw,
            encryption_algorithm=serialization.NoEncryption(),
        )

        return ED25519Key(_private_key_pem=private_pem, _private_key=private_bytes)


@dataclass
class EthereumKey(KeyPair):
    """ Class for loading, generating and handling public/private key pairs using secp256k1 algorithm """

    def __post_init__(self):
        self._algorithm = SECP256K1_ETHEREUM_KEY_ALGORITHM

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
            private_key_object = ecdsa.SigningKey.from_pem(
                self._private_key_pem.decode("ascii")
            )
            self._private_key = private_key_object.to_string()  # is to_bytes
        return self._private_key

    @property
    def public_key_pem(self):
        if self._public_key_pem is None:
            public_key_object = ecdsa.VerifyingKey.from_string(
                self.public_key
            )  # is from_bytes
            self._public_key_pem = public_key_object.to_pem().encode("ascii")
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
        # Note: holdover from Python2, actual bytes
        private_key = private_key_object.to_string()
        return EthereumKey(_private_key=private_key)

    @staticmethod
    def from_private_key_path(private_key_pem_path: Union[str, Path]) -> "KeyPair":
        """ Creates EthereumKey object from private key file in pem format """
        private_key_pem = read_binary_file(private_key_pem_path)
        return EthereumKey(_private_key_pem=private_key_pem)

    @staticmethod
    def from_private_key(private_key: bytes) -> "KeyPair":
        """ Creates EthereumKey object from private key in bytes """
        ecdsa.SigningKey.from_string(private_key)
        return EthereumKey(_private_key=private_key)
