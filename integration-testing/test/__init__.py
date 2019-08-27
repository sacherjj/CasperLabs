import sys
import os
import logging
import subprocess
from test.cl_node.client_parser import parse_show_blocks, parse_show_deploys, parse
from pyblake2 import blake2b


def contract_hash(from_addr_base16: str, nonce: int, function_counter: int) -> bytes:
    """
    Should match what the EE does:
        blake2b256( [0;32] ++ [0;8] ++ [0;4] )
        pk ++ nonce ++ function_counter
    """

    def hash(data: bytes) -> bytes:
        h = blake2b(digest_size=32)
        h.update(data)
        return h.digest()

    account_bytes = bytes.fromhex(from_addr_base16)
    nonce_bytes = nonce.to_bytes(8, sys.byteorder)
    counter_bytes = function_counter.to_bytes(4, sys.byteorder)

    assert (
        sys.byteorder == "little"
    )  # Tests passed with little; not sure if it affects anything else.
    assert len(account_bytes) == 32
    assert len(nonce_bytes) == 8
    assert len(counter_bytes) == 4

    data = account_bytes + nonce_bytes + counter_bytes

    return hash(data)


class CLIErrorExit(Exception):
    def __init__(self, cp, output):
        self.cp = cp
        self.output = output

    def __str__(self):
        return f"{self.cp}: {self.output}"


class CLI:
    def __init__(self, node, cli_cmd="casperlabs_client"):
        self.node = node
        self.host = (
            os.environ.get("TAG_NAME", None) and node.container_name or "localhost"
        )
        self.port = node.grpc_external_docker_port
        self.cli_cmd = cli_cmd

    def expand_args(self, args):
        def _args(
            args,
            connection_details=["--host", f"{self.host}", "--port", f"{self.port}"],
        ):
            return [str(a) for a in connection_details + list(args)]

        return "--help" in args and _args(args, []) or _args(args)

    def parse_output(self, command, binary_output):

        if command in ("make-deploy", "sign-deploy"):
            return binary_output

        output = binary_output.decode("utf-8")

        if command == "send-deploy":
            return output.split()[2]

        if command in ("deploy", "propose"):
            return output.split()[3]

        if command == "show-blocks":
            return parse_show_blocks(output)

        if command == "show-deploys":
            return parse_show_deploys(output)

        if command in ("show-deploy", "show-block", "query-state"):
            return parse(output)

        return output

    def __call__(self, *args):
        command_line = [str(self.cli_cmd)] + self.expand_args(args)
        logging.info(f"EXECUTING []: {command_line}")
        logging.info(f"EXECUTING: {' '.join(command_line)}")
        cp = subprocess.run(
            command_line, stdout=subprocess.PIPE, stderr=subprocess.PIPE
        )
        binary_output = cp.stdout
        if cp.returncode != 0:
            output = binary_output
            try:
                output = binary_output.decode("utf-8")
            except UnicodeDecodeError:
                pass
            raise CLIErrorExit(cp, output)

        return self.parse_output(args[0], binary_output)


class DockerCLI(CLI):
    def __call__(self, *args):
        logging.info(f"EXECUTING []: {args}")
        self.host = self.node.container_name
        command = " ".join(self.expand_args(args))
        logging.info(f"EXECUTING: {command}")
        binary_output = self.node.d_client.invoke_client(
            command, decode_stdout=False, add_host=False
        )
        return self.parse_output(args[0], binary_output)
