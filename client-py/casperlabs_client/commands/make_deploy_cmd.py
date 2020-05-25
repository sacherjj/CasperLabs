import sys

from casperlabs_client.commands import deploy_cmd
from casperlabs_client.io import write_binary_file
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
def method(casperlabs_client, args):
    kwargs = deploy_cmd.process_kwargs(args, private_key_accepted=False)
    deploy = casperlabs_client.make_deploy(**kwargs)
    data = deploy.SerializeToString()
    if not args.deploy_path:
        sys.stdout.buffer.write(data)
    else:
        write_binary_file(args.deploy_path, data)
