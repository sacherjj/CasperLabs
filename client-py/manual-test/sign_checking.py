from eth_keys import KeyAPI
from casperlabs_client.key_holders import SECP256K1Key
import ecdsa

key_api = KeyAPI()
pk_hex = "805450db5849a42806fb909a78e6ab36448b9f0e00a2a036d6fffe815e79c97e"

pk = key_api.PrivateKey(bytes.fromhex(pk_hex))
print(pk.to_bytes().hex())
kh = SECP256K1Key(private_key=bytes.fromhex(pk_hex))
print(kh.private_key.hex())
sign_bytes = bytes.fromhex(
    "9834876dcfb05cb167a5c24953eba58c4ac89b1adf57f28f2f9d09af107ee8f0"
)
signature = key_api.ecdsa_sign(sign_bytes, pk)
print(signature.to_bytes().hex())
signatureb = kh.sign(sign_bytes)
print(signatureb.hex())
signatureb = kh.sign(sign_bytes)
print(signatureb.hex())

pk = ecdsa.SigningKey.from_string(bytes.fromhex(pk_hex), ecdsa.SECP256k1)
print(pk.sign_digest(sign_bytes).hex())
print(pk.sign_digest(sign_bytes, sigencode=ecdsa.util.sigencode_der_canonize).hex())
print(pk.sign_digest(sign_bytes, sigencode=ecdsa.util.sigdecode_strings).hex())
print(pk.sign_digest(sign_bytes, sigencode=ecdsa.util.sigencode_strings_canonize).hex())
# pk
# pk.public_key
# signature
# pk.public_key.to_checksum_address()
# signature.verify_msg(sign_bytes, pk.public_key)
# signature.recover_public_key_from_msg(sign_bytes) == pk.public_key
