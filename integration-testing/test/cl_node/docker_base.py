from __future__ import annotations

import json
import logging
import os
import threading
from dataclasses import dataclass
from multiprocessing import Process, Queue
from queue import Empty
from typing import Any, Dict, Optional, Tuple, Union
from docker import DockerClient
from docker.models.containers import Container
from test.cl_node.casperlabs_accounts import GENESIS_ACCOUNT
from test.cl_node.common import random_string
from test.cl_node.errors import CommandTimeoutError, NonZeroExitCodeError


def humanify(line):
    """
    Decode json dump of execution engine's structured log and render a human friendly line,
    containing, together with prefix rendered by the Python test framework, all useful
    information. The original dictionary in the EE structured log looks like follows:

        {'timestamp': '2019-06-08T17:51:35.308Z', 'process_id': 1, 'process_name': 'casperlabs-engine-grpc-server', 'host_name': 'execution-engine-0-mlgtn', 'log_level': 'Info', 'priority': 5, 'message_type': 'ee-structured', 'message_type_version': '1.0.0', 'message_id': '14039567985248808663', 'description': 'starting Execution Engine Server', 'properties': {'message': 'starting Execution Engine Server', 'message_template': '{message}'}}
    """
    if "execution-engine-" not in line:
        return line
    try:
        _, payload = line.split("payload=")
    except Exception:
        return line

    d = json.loads(payload)
    return " ".join(str(d[k]) for k in ("log_level", "description"))


class LoggingThread(threading.Thread):
    def __init__(
        self,
        terminate_thread_event: threading.Event,
        container: Container,
        logger: logging.Logger,
    ) -> None:
        super().__init__()
        self.terminate_thread_event = terminate_thread_event
        self.container = container
        self.logger = logger

    def run(self) -> None:
        containers_log_lines_generator = self.container.logs(stream=True, follow=True)
        try:
            while True:
                if self.terminate_thread_event.is_set():
                    break
                line = next(containers_log_lines_generator)
                s = line.decode("utf-8").rstrip()
                self.logger.info(f"  {self.container.name}: {humanify(s)}")
        except StopIteration:
            pass


@dataclass
class DockerConfig:
    """
    This holds all information that will be needed for creating both docker containers for a CL_Node
    """

    docker_client: "DockerClient"
    node_private_key: str
    node_public_key: str = None
    node_env: dict = None
    network: Optional[Any] = None
    number: int = 0
    rand_str: Optional[str] = None
    volumes: Optional[Dict[str, Dict[str, str]]] = None
    command_timeout: int = 180
    mem_limit: str = "4G"
    is_bootstrap: bool = False
    is_validator: bool = True
    is_signed_deploy: bool = True
    bootstrap_address: Optional[str] = None
    use_new_gossiping: bool = True
    genesis_public_key_path: str = None

    def __post_init__(self):
        if self.rand_str is None:
            self.rand_str = random_string(5)
        if self.node_env is None:
            self.node_env = {
                "RUST_BACKTRACE": "full",
                "CL_LOG_LEVEL": os.environ.get("CL_LOG_LEVEL", "INFO"),
                "CL_CASPER_IGNORE_DEPLOY_SIGNATURE": "false",
                "CL_SERVER_NO_UPNP": "true",
                "CL_VERSION": "test",
            }

    def node_command_options(self, server_host: str) -> dict:
        bootstrap_path = "/root/.casperlabs/bootstrap"
        options = {
            "--server-default-timeout": "10second",
            "--server-host": server_host,
            "--casper-validator-private-key": self.node_private_key,
            "--grpc-socket": "/root/.casperlabs/sockets/.casper-node.sock",
            "--metrics-prometheus": "",
            "--tls-certificate": f"{bootstrap_path}/node-{self.number}.certificate.pem",
            "--tls-key": f"{bootstrap_path}/node-{self.number}.key.pem",
        }
        # if self.is_validator:
        #     options['--casper-validator-private-key-path'] = f'{bootstrap_path}/validator-{self.number}-private.pem'
        #     options['--casper-validator-public-key-path'] = f'{bootstrap_path}/validator-{self.number}-public.pem'
        if self.bootstrap_address:
            options["--server-bootstrap"] = self.bootstrap_address
        if self.is_bootstrap:
            gen_acct_key_file = GENESIS_ACCOUNT.public_key_filename
            options[
                "--casper-genesis-account-public-key-path"
            ] = f"/root/.casperlabs/accounts/{gen_acct_key_file}"
            options["--casper-initial-tokens"] = 100000000000
        if self.node_public_key:
            options["--casper-validator-public-key"] = self.node_public_key
        if self.use_new_gossiping:
            options["--server-use-gossiping"] = ""
        return options


class DockerBase:
    """
    This holds the common base functionality for docker images.

    Rather than constants, we build up properties based on values.  Some only work in subclasses.
    """

    DOCKER_BASE_NAME = "casperlabs"

    def __init__(self, config: DockerConfig, socket_volume: str) -> None:
        self.config = config
        self.socket_volume = socket_volume
        self.connected_networks = []

        self.docker_tag: str = "test"
        if self.is_in_docker:
            self.docker_tag = os.environ.get("TAG_NAME")
        self.container = self._get_container()

    @property
    def is_in_docker(self) -> bool:
        return os.environ.get("TAG_NAME") is not None

    @property
    def image_name(self) -> str:
        return f"{self.DOCKER_BASE_NAME}/{self.container_type}:{self.docker_tag}"

    @property
    def name(self) -> str:
        # TODO: For compatibility only with old methods.  Once name -> container_name in old methods, remove.
        return self.container_name

    @property
    def container_name(self) -> str:
        return f"{self.container_type}-{self.config.number}-{self.config.rand_str}-{self.docker_tag}"

    @property
    def container_type(self) -> str:
        # Raising exception rather than abstract method eliminates requiring an __init__ in child classes.
        raise NotImplementedError("No implementation of container_type")

    @property
    def host_mount_dir(self) -> str:
        return f"/tmp/resources_{self.docker_tag}_{self.config.number}_{self.config.rand_str}"

    @property
    def bonds_file(self) -> str:
        return f"{self.host_mount_dir}/bonds.txt"

    @property
    def host_genesis_dir(self) -> str:
        return f"{self.host_mount_dir}/genesis"

    @property
    def host_bootstrap_dir(self) -> str:
        return f"{self.host_mount_dir}/bootstrap_certificate"

    @property
    def host_accounts_dir(self) -> str:
        return f"{self.host_mount_dir}/accounts"

    @property
    def docker_client(self) -> DockerClient:
        return self.config.docker_client

    def _get_container(self):
        # Raising exception rather than abstract method eliminates requiring an __init__ in child classes.
        raise NotImplementedError("No implementation of _get_container")

    def stop(self):
        self.container.stop()

    def start(self):
        self.container.start()

    def __repr__(self):
        return f"<{self.__class__.__name__} {self.container_name}"

    def exec_run(
        self, cmd: Union[Tuple[str, ...], str], stderr=True
    ) -> Tuple[int, str]:
        queue: Queue = Queue(1)

        def execution():
            r = self.container.exec_run(cmd, stderr=stderr)
            queue.put((r.exit_code, r.output.decode("utf-8")))

        process = Process(target=execution)

        logging.info("COMMAND {} {}".format(self.container_name, cmd))

        process.start()

        try:
            exit_code, output = queue.get(True, None)
            if exit_code != 0:
                logging.warning(
                    "EXITED {} {} {}".format(self.container.name, cmd, exit_code)
                )
            logging.debug("OUTPUT {}".format(repr(output)))
            return exit_code, output
        except Empty:
            process.terminate()
            process.join()
            raise CommandTimeoutError(cmd, self.config.command_timeout)

    def shell_out(self, *cmd: str, stderr=True) -> str:
        exit_code, output = self.exec_run(cmd, stderr=stderr)
        if exit_code != 0:
            raise NonZeroExitCodeError(command=cmd, exit_code=exit_code, output=output)
        return output

    def network_from_name(self, network_name: str):
        nets = self.docker_client.networks.list(names=[network_name])
        if nets:
            network = nets[0]
            return network
        raise Exception(f"Docker network '{network_name}' not found.")

    def connect_to_network(self, network_name: str) -> None:
        self.connected_networks.append(network_name)
        network = self.network_from_name(network_name)
        network.connect(self.container)

    def disconnect_from_network(self, network_name: str) -> None:
        try:
            self.connected_networks.remove(network_name)
            network = self.network_from_name(network_name)
            network.disconnect(self.container)
        except Exception as e:
            logging.error(
                f"Error disconnecting {self.container_name} from {network_name}: {e}"
            )

    def cleanup(self) -> None:
        if self.container:
            for network_name in self.connected_networks:
                self.disconnect_from_network(network_name)
            try:
                self.container.remove(force=True, v=True)
            except Exception as e:
                logging.warning(f"Error removing container {self.container_name}: {e}")


class LoggingDockerBase(DockerBase):
    """
    This adds logging to DockerBase
    """

    def __init__(self, config: DockerConfig, socket_volume: str) -> None:
        super().__init__(config, socket_volume)
        self.terminate_background_logging_event = threading.Event()
        self._start_logging_thread()
        self._truncatedLength = 0

    def _start_logging_thread(self):
        self.background_logging = LoggingThread(
            container=self.container,
            logger=logging.getLogger("peers"),
            terminate_thread_event=self.terminate_background_logging_event,
        )
        self.background_logging.start()

    def start(self):
        super().start()
        if not self.background_logging.is_alive():
            self._start_logging_thread()

    @property
    def container_type(self) -> str:
        return super().container_type

    def _get_container(self):
        return super()._get_container()

    def logs(self) -> str:
        return self.container.logs().decode("utf-8")[self._truncatedLength :]

    def truncate_logs(self):
        self._truncatedLength = len(self.container.logs().decode("utf-8"))

    def cleanup(self):
        super().cleanup()
        # Terminate the logging after cleaning up containers.
        # Otherwise the thread may be locked waiting for another log line, rather than get
        # the StopIteration exception when the container shuts down.
        self.terminate_background_logging_event.set()
        self.background_logging.join()
