from test.cl_node.docker_base import LoggingDockerBase


class DockerExecutionEngine(LoggingDockerBase):
    @property
    def container_type(self) -> str:
        return "execution-engine"

    @property
    def command(self) -> str:
        payment_flag = "-x" if self.config.is_payment_code_enabled else ""
        return f".casperlabs/sockets/.casper-node.sock {payment_flag}"

    @property
    def volumes(self) -> dict:
        return {
            self.config.socket_volume: {
                "bind": "/opt/docker/.casperlabs/sockets",
                "mode": "rw",
            }
        }

    def _get_container(self):
        container = self.config.docker_client.containers.run(
            self.image_name,
            name=self.container_name,
            user="root",
            detach=True,
            command=self.command,
            network=self.config.network,
            volumes=self.volumes,
            hostname=self.container_name,
        )
        return container
