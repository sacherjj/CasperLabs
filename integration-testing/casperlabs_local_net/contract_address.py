from pyblake2 import blake2b


def contract_address(deploy_hash_base16: str, fn_store_id: int) -> bytes:
    """
    Should match what the EE does (new_function_address)
    //32 bytes for deploy hash + 4 bytes ID

        blake2b256( [0;32] ++ [0;4] )
        deploy_hash ++ fn_store_id
    """

    def hash(data: bytes) -> bytes:
        h = blake2b(digest_size=32)
        h.update(data)
        return h.digest()

    deploy_hash_bytes = bytes.fromhex(deploy_hash_base16)
    counter_bytes = fn_store_id.to_bytes(4, "little")

    data = deploy_hash_bytes + counter_bytes

    return hash(data)
