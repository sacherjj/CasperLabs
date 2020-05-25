from pathlib import Path

from casperlabs_client.arg_types import directory_for_write
from casperlabs_client.io import write_file, write_binary_file
from casperlabs_client.decorators import guarded_command

from casperlabs_client.crypto import generate_keys
from casperlabs_client.utils import encode_base64

NAME: str = "keygen"
HELP: str = """Generate keys.

Usage: casperlabs-client keygen <existing output directory>
Command will override existing files!
Generated files:
   account-id          # validator ID in Base64 format; can be used in accounts.csv
                         # derived from validator.public.pem
   account-id-hex      # validator ID in hex, derived from validator.public.pem
   account-private.pem # ed25519 private key
   account-public.pem  # ed25519 public key"""
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
    private_path = directory / "account-private.pem"
    public_path = directory / "account-public.pem"
    id_path = directory / "account-id"
    id_hex_path = directory / "account-id-hex"

    (private_pem, public_pem, public_bytes) = generate_keys()
    write_binary_file(private_path, private_pem)
    write_binary_file(public_path, public_pem)
    write_file(id_path, encode_base64(public_bytes))
    write_file(id_hex_path, public_bytes.hex())

    print(f"Keys successfully created in directory: {str(directory.absolute())}")
