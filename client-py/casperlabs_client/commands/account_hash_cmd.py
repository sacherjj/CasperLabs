import sys
from typing import Dict

from casperlabs_client import consts, CasperLabsClient, io
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
            choices=consts.SUPPORTED_KEY_ALGORITHMS,
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
def method(casperlabs_client: CasperLabsClient, args: Dict):
    algorithm = args.get("algorithm")
    public_key_path = args.get("public_key")
    account_hash = casperlabs_client.account_hash(algorithm, public_key_path)

    file_path = args.get("file_path")
    if file_path:
        io.write_binary_file(file_path, account_hash)
    else:
        sys.stdout.buffer.write(account_hash)
