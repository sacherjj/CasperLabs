import sys

from .. import consensus_pb2 as consensus

from casperlabs_client.crypto import read_pem_key
from casperlabs_client.utils import guarded_command

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
def method(casperlabs_client, args):
    deploy = consensus.Deploy()
    if args.deploy_path:
        with open(args.deploy_path, "rb") as input_file:
            deploy.ParseFromString(input_file.read())
    else:
        deploy.ParseFromString(sys.stdin.read())

    deploy = casperlabs_client.sign_deploy(
        deploy, read_pem_key(args.public_key), args.private_key
    )

    if not args.signed_deploy_path:
        sys.stdout.write(deploy.SerializeToString())
    else:
        with open(args.signed_deploy_path, "wb") as output_file:
            output_file.write(deploy.SerializeToString())
