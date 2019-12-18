import json
import logging
import os
import threading
from multiprocessing import Process, Queue
from queue import Empty
from typing import Tuple, Union
from docker import DockerClient
from docker.models.containers import Container

from casperlabs_local_net.errors import CommandTimeoutError, NonZeroExitCodeError
from casperlabs_local_net.docker_config import DockerConfig


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


class DockerBase:
    """
    This holds the common base functionality for docker images.

    Rather than constants, we build up properties based on values.  Some only work in subclasses.
    """

    DOCKER_BASE_NAME = "casperlabs"

    def __init__(self, config: DockerConfig) -> None:
        self.config = config
        self.connected_networks = []

        self.container = self._get_container()

    @property
    def docker_tag(self) -> str:
        if self.is_in_docker:
            return os.environ.get("TAG_NAME")
        elif self.config.custom_docker_tag is not None:
            return self.config.custom_docker_tag
        else:
            return "latest"

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
    def host_keys_dir(self) -> str:
        return f"{self.host_mount_dir}/{self.config.keys_directory}"

    @property
    def host_chainspec_dir(self) -> str:
        # Mirror the default chainspec packaged in the node so we can apply partial overrides.
        return f"{self.host_mount_dir}/{self.config.chainspec_directory}"

    @property
    def host_etc_casperlabs_dir(self) -> str:
        return f"{self.host_mount_dir}/{self.config.etc_casperlabs_directory}"

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

    def __init__(self, config: DockerConfig) -> None:
        super().__init__(config)
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
