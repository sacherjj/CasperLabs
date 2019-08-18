from test.cl_node.docker_base import LoggingDockerBase


class DockerExecutionEngine(LoggingDockerBase):
    EXECUTION_ENGINE_COMMAND = ".casperlabs/sockets/.casper-node.sock"
    EE_WITH_PAYMENT_COMMAND = ".casperlabs/sockets/.casper-node.sock -x"

    @property
    def container_type(self) -> str:
        return "execution-engine"

    def _get_container(self):
        if self.config.volumes is not None:
            volumes = self.config.volumes
        else:
            volumes = {
                self.socket_volume: {
                    "bind": "/opt/docker/.casperlabs/sockets",
                    "mode": "rw",
                }
            }
        command = (
            self.config.is_payment_code_enabled
            and self.EE_WITH_PAYMENT_COMMAND
            or self.EXECUTION_ENGINE_COMMAND
        )
        container = self.config.docker_client.containers.run(
            self.image_name,
            name=self.container_name,
            user="root",
            detach=True,
            command=command,
            network=self.config.network,
            volumes=volumes,
            hostname=self.container_name,
        )
        return container
