import sys
from typing import Dict

from casperlabs_client import consensus_pb2 as consensus, io, CasperLabsClient

from casperlabs_client.crypto import read_pem_key
from casperlabs_client.decorators import guarded_command

NAME: str = "sign-deploy"
HELP: str = "Cryptographically signs a deploy. The signature is appended to existing approvals."
OPTIONS = [
    [
        ("-o", "--signed-deploy-path"),
        dict(
            required=False,
            default=None,
            help=(
                "Path to the file where signed deploy will be saved. "
                "Optional, if not provided the deploy will be printed to STDOUT."
            ),
        ),
    ],
    [
        ("-i", "--deploy-path"),
        dict(required=False, default=None, help="Path to the deploy file."),
    ],
    [
        ("--private-key",),
        dict(required=True, help="Path to the file with account private key (Ed25519)"),
    ],
    [
        ("--public-key",),
        dict(required=True, help="Path to the file with account public key (Ed25519)"),
    ],
]


@guarded_command
def method(casperlabs_client: CasperLabsClient, args: Dict):
    if args.get("deploy_path"):
        deploy = casperlabs_client.sign_deploy(
            None,
            read_pem_key(args.get("public_key")),
            args.get("private_key"),
            args.get("deploy_path"),
        )
    else:
        deploy = consensus.Deploy()
        deploy.ParseFromString(sys.stdin.read())

    if not args.get("signed_deploy_path"):
        sys.stdout.write(deploy.SerializeToString())
    else:
        io.write_binary_file(args.get("signed_deploy_path"), deploy.SerializeToString())
