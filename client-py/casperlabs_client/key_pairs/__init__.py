from .key_pair import KeyPair
from .ed25519 import ED25519Key
from .ethereum import EthereumKey

from ..consts import (
    ED25519_KEY_ALGORITHM,
    SECP256K1_ETHEREUM_KEY_ALGORITHM,
    SUPPORTED_KEY_ALGORITHMS,
)


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
