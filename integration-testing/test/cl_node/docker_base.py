from dataclasses import dataclass
import os
import threading
import logging
from multiprocessing import Process, Queue
from queue import Empty
from typing import (
    Optional,
    Dict,
    Any,
    Union,
    Tuple,
)

from test.cl_node.errors import (
    NonZeroExitCodeError,
    CommandTimeoutError,
)

from test.cl_node.common import random_string


class LoggingThread(threading.Thread):
    def __init__(self, terminate_thread_event: "Event", container: "Container", logger: "Logger") -> None:
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
                self.logger.info(f"  {self.container.name}: {line.decode('utf-8').rstrip()}")
        except StopIteration:
            pass


CI_BUILD_NUMBER = 'DRONE_BUILD_NUMBER'


@dataclass
class DockerConfig:
    """
    This holds all information that will be needed for creating both docker containers for a CL_Node
    """
    docker_client: 'DockerClient'
    node_private_key: str
    node_public_key: str = None
    network: Optional[Any] = None
    number: int = 0
    rand_str: Optional[str] = None
    volumes: Optional[Dict[str, Dict[str, str]]] = None
    command_timeout: int = 180
    mem_limit: str = '4G'
    is_bootstrap: bool = False
    is_validator: bool = True
    bootstrap_address: Optional[str] = None
    is_gossiping: bool = False

    def __post_init__(self):
        if self.rand_str is None:
            self.rand_str = random_string(5)

    def node_command_options(self, server_host: str) -> dict:
        bootstrap_path = '/root/.casperlabs/bootstrap'
        options = {'--server-default-timeout': 10000,
                   '--server-host': server_host,
                   '--casper-validator-private-key': self.node_private_key,
                   '--grpc-socket': '/root/.casperlabs/sockets/.casper-node.sock',
                   '--metrics-prometheus': '',
                   '--tls-certificate': f'{bootstrap_path}/node-{self.number}.certificate.pem',
                   '--tls-key': f'{bootstrap_path}/node-{self.number}.key.pem'}
        # if self.is_validator:
        #     options['--casper-validator-private-key-path'] = f'{bootstrap_path}/validator-{self.number}-private.pem'
        #     options['--casper-validator-public-key-path'] = f'{bootstrap_path}/validator-{self.number}-public.pem'
        if self.bootstrap_address:
            options['--server-bootstrap'] = self.bootstrap_address
        if self.node_public_key:
            options['--casper-validator-public-key'] = self.node_public_key
        if self.is_gossiping:
            options['--server-use-gossiping'] = ''
        return options

    @property
    def grpc_port(self) -> int:
        """
        Each node will get a port for grpc calls starting at 40500.
        """
        return 40500 + self.number


class DockerBase:
    """
    This holds the common base functionality for docker images.

    Rather than constants, we build up properties based on values.  Some only work in subclasses.
    """

    DOCKER_BASE_NAME = 'casperlabs'

    def __init__(self, config: DockerConfig, socket_volume: str) -> None:
        self.config = config
        self.socket_volume = socket_volume

        self.docker_tag: str = 'test'
        if os.environ.get(CI_BUILD_NUMBER) is not None:
            self.docker_tag = f'DRONE-{os.environ.get(CI_BUILD_NUMBER)}'

        self.container = self._get_container()

    @property
    def image_name(self) -> str:
        return f'{self.DOCKER_BASE_NAME}/{self.container_type}:{self.docker_tag}'

    @property
    def name(self) -> str:
        # TODO: For compatibility only with old methods.  Once name -> container_name in old methods, remove.
        return self.container_name

    @property
    def container_name(self) -> str:
        return f'{self.container_type}-{self.config.number}-{self.config.rand_str}'

    @property
    def container_type(self) -> str:
        raise NotImplementedError('No implementation of container_type')

    @property
    def host_mount_dir(self) -> str:
        return f'/tmp/resources_{self.docker_tag}_{self.config.number}_{self.config.rand_str}'

    @property
    def bonds_file(self) -> str:
        return f'{self.host_mount_dir}/bonds.txt'

    @property
    def host_genesis_dir(self) -> str:
        return f'{self.host_mount_dir}/genesis'

    @property
    def host_bootstrap_dir(self) -> str:
        return f'{self.host_mount_dir}/bootstrap_certificate'

    def _get_container(self):
        raise NotImplementedError('No implementation of _get_container')

    def stop(self):
        self.container.stop()

    def start(self):
        self.container.start()

    def __repr__(self):
        return f"<{self.__class__.__name__} {self.container_name}"

    def exec_run(self, cmd: Union[Tuple[str, ...], str], stderr=True) -> Tuple[int, str]:
        queue: Queue = Queue(1)

        def execution():
            r = self.container.exec_run(cmd, stderr=stderr)
            queue.put((r.exit_code, r.output.decode('utf-8')))

        process = Process(target=execution)

        logging.info("COMMAND {} {}".format(self.container_name, cmd))

        process.start()

        try:
            exit_code, output = queue.get(True, None)
            if exit_code != 0:
                logging.warning("EXITED {} {} {}".format(self.container.name, cmd, exit_code))
            logging.debug('OUTPUT {}'.format(repr(output)))
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

    def cleanup(self) -> None:
        if self.container:
            self.container.remove(force=True, v=True)


class LoggingDockerBase(DockerBase):
    """
    This adds logging to DockerBase
    """

    def __init__(self, config: DockerConfig, socket_volume: str) -> None:
        super().__init__(config, socket_volume)
        self.terminate_background_logging_event = threading.Event()
        self._start_logging_thread()

    def _start_logging_thread(self):
        self.background_logging = LoggingThread(
            container=self.container,
            logger=logging.getLogger('peers'),
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
        return self.container.logs().decode('utf-8')

    def cleanup(self):
        super().cleanup()
        # Terminate the logging after cleaning up containers.
        # Otherwise the thread may be locked waiting for another log line, rather than get
        # the StopIteration exception when the container shuts down.
        self.terminate_background_logging_event.set()
        self.background_logging.join()
