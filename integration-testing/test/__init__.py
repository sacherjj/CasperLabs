
import sys
from pyblake2 import blake2b

def contract_hash(
    from_addr_base16: str,
    nonce: int,
    function_counter: int) -> bytes:
    """
    Should match what the EE does:
        blake2b256( [0;32] ++ [0;8] ++ [0;4] )
        pk ++ nonce ++ function_counter
    """

    def hash(data: bytes) -> bytes:
        h = blake2b(digest_size=32)
        h.update(data)
        return h.digest()

    account_bytes = bytes.fromhex(from_addr_base16)
    nonce_bytes = nonce.to_bytes(8, sys.byteorder)
    counter_bytes = function_counter.to_bytes(4, sys.byteorder)

    assert(sys.byteorder == 'little') # Tests passed with little; not sure if it affects anything else.
    assert(len(account_bytes) == 32)
    assert(len(nonce_bytes) == 8)
    assert(len(counter_bytes) == 4)

    data = account_bytes + nonce_bytes + counter_bytes

    return hash(data)



