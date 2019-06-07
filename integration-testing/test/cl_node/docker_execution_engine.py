from test.cl_node.docker_base import LoggingDockerBase


class DockerExecutionEngine(LoggingDockerBase):
    EXECUTION_ENGINE_COMMAND = ".casperlabs/sockets/.casper-node.sock validate-nonce"

    @property
    def container_type(self) -> str:
        return 'execution-engine'

    def _get_container(self):
        if self.config.volumes is not None:
            volumes = self.config.volumes
        else:
            volumes = {
                self.socket_volume: {
                    "bind": "/opt/docker/.casperlabs/sockets",
                    "mode": "rw"
                }
            }
        container = self.config.docker_client.containers.run(
            self.image_name,
            name=self.container_name,
            user='root',
            detach=True,
            command=self.EXECUTION_ENGINE_COMMAND,
            network=self.config.network,
            volumes=volumes,
            hostname=self.container_name,
        )
        return container
