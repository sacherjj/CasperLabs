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
    deploy = casperlabs_client.make_deploy(**args)
    data = deploy.SerializeToString()
    if not args.get("deploy_path"):
        sys.stdout.buffer.write(data)
    else:
        io.write_binary_file(args.get("deploy_path"), data)
