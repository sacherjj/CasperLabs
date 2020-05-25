import sys

from casperlabs_client import CasperLabsClient
from casperlabs_client.consts import SUPPORTED_KEY_ALGORITHMS
from casperlabs_client.crypto import read_pem_key
from casperlabs_client.io import write_binary_file
from casperlabs_client.decorators import guarded_command

NAME: str = "account-hash"
HELP: str = (
    "Calculate and return the account hash from public key and algorithm pair."
    "Saves in file if path given, otherwise uses stdout."
)
OPTIONS = (
    (
        ("-a", "--algorithm"),
        dict(
            required=True,
            type=str,
            choices=SUPPORTED_KEY_ALGORITHMS,
            help="Algorithm used for public key generation.",
        ),
    ),
    (
        ("-k", "--public-key"),
        dict(required=True, type=str, help="Path to the file with account public key"),
    ),
    (
        ("-o", "--file-path"),
        dict(
            required=False,
            help=(
                "Path to the file where account hash will be saved. "
                "Optional, if not provided the account hash will be printed to STDOUT."
            ),
        ),
    ),
)


@guarded_command
def method(casperlabs_client: CasperLabsClient, args):
    print(args)
    algorithm = getattr(args, "algorithm")
    public_key_path = getattr(args, "public_key")
    public_key = read_pem_key(public_key_path)
    account_hash = casperlabs_client.account_hash(algorithm, public_key)
    file_path = getattr(args, "file_path")
    if file_path:
        write_binary_file(file_path, account_hash)
    else:
        sys.stdout.buffer.write(account_hash)
