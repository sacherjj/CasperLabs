import sys
from typing import Dict

from casperlabs_client import io, CasperLabsClient
from casperlabs_client.commands import deploy_cmd
from casperlabs_client.decorators import guarded_command


NAME: str = "make-deploy"
HELP: str = "Constructs a deploy that can be signed and sent to a node."
OPTIONS = [
    [
        ("-o", "--deploy-path"),
        dict(
            required=False,
            help=(
                "Path to the file where deploy will be saved. "
                "Optional, if not provided the deploy will be printed to STDOUT."
            ),
        ),
    ]
] + deploy_cmd.OPTIONS


@guarded_command
def method(casperlabs_client: CasperLabsClient, args: Dict):
    deploy = casperlabs_client.make_deploy(
        from_addr=args.get("from"),
        payment=args.get("payment"),
        session=args.get("session"),
        public_key=args.get("public_key"),
        private_key=args.get("private_key"),
        session_args=args.get("session_args"),
        payment_args=args.get("payment_args"),
        payment_amount=args.get("payment_amount"),
        payment_hash=args.get("payment_hash"),
        payment_name=args.get("payment_name"),
        payment_uref=args.get("payment_uref"),
        session_hash=args.get("session_hash"),
        session_name=args.get("session_name"),
        session_uref=args.get("session_uref"),
        ttl_millis=args.get("ttl_millis"),
        dependencies=args.get("dependencies"),
        chain_name=args.get("chain_name"),
        algorithm=args.get("algorithm"),
    )
    data = deploy.SerializeToString()
    if not args.get("deploy_path"):
        sys.stdout.buffer.write(data)
    else:
        io.write_binary_file(args.get("deploy_path"), data)
