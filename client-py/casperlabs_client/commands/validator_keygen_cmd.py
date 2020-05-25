from pathlib import Path

from casperlabs_client.arg_types import directory_for_write
from casperlabs_client.io import write_file, write_binary_file
from casperlabs_client.decorators import guarded_command
from casperlabs_client.crypto import (
    generate_keys,
    generate_key_pair,
    generate_certificates,
    public_address,
)
from casperlabs_client.utils import encode_base64

NAME: str = "validator-keygen"
HELP: str = """Generate keys.

Usage: casperlabs-client keygen <existing output directory>
Command will override existing files!
Generated files:
   node-id               # node ID as in casperlabs://c0a6c82062461c9b7f9f5c3120f44589393edf31@<NODE ADDRESS>?protocol=40400&discovery=40404
                         # derived from node.key.pem
   node.certificate.pem  # TLS certificate used for node-to-node interaction encryption
                         # derived from node.key.pem
   node.key.pem          # secp256r1 private key
   validator-id          # validator ID in Base64 format; can be used in accounts.csv
                         # derived from validator.public.pem
   validator-id-hex      # validator ID in hex, derived from validator.public.pem
   validator-private.pem # ed25519 private key
   validator-public.pem  # ed25519 public key"""
OPTIONS = [
    [
        ("directory",),
        dict(
            type=directory_for_write,
            help="Output directory for keys. Should already exists.",
        ),
    ]
]


@guarded_command
def method(casperlabs_client, args):
    directory = Path(args.directory).resolve()
    validator_private_path = directory / "validator-private.pem"
    validator_pub_path = directory / "validator-public.pem"
    validator_id_path = directory / "validator-id"
    validator_id_hex_path = directory / "validator-id-hex"
    node_priv_path = directory / "node.key.pem"
    node_cert_path = directory / "node.certificate.pem"
    node_id_path = directory / "node-id"

    (
        validator_private_pem,
        validator_public_pem,
        validator_public_bytes,
    ) = generate_keys()
    write_binary_file(validator_private_path, validator_private_pem)
    write_binary_file(validator_pub_path, validator_public_pem)
    write_file(validator_id_path, encode_base64(validator_public_bytes))
    write_file(validator_id_hex_path, validator_public_bytes.hex())

    private_key, public_key = generate_key_pair()

    node_cert, key_pem = generate_certificates(private_key, public_key)

    write_binary_file(node_priv_path, key_pem)
    write_binary_file(node_cert_path, node_cert)

    write_file(node_id_path, public_address(public_key))
    print(f"Keys successfully created in directory: {str(directory.absolute())}")
