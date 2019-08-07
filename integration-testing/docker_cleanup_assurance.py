import os
import docker

from docker.errors import APIError


def cleanup(tag_name: str):
    dc = docker.client.from_env()
    for container in dc.containers.list(all=True, sparse=True):
        container.reload()
        if container.name:
            # Don't remove testing image that we may be running in.
            if "integration-testing" in container.name:
                continue
            # Don't remove docker-compose container
            if "test-" == container.name[:5]:
                continue
            if tag_name == container.name[-len(tag_name) :]:
                try:
                    print(f"REMOVING ABANDONED DOCKER CONTAINER: {container.name}")
                    container.remove(force=True)
                except APIError as e:
                    print(f"Error removing container {container.name}: {e}")
    for network in dc.networks.list():
        # We do not want to remove the PythonClient networks ending in -0 thru -10,
        # so matching tag_name end only
        if tag_name == network.name[-len(tag_name) :]:
            try:
                print(f"REMOVING ABANDONED DOCKER NETWORK: {network.name}")
                network.remove()
            except APIError as e:
                print(f"Error removing network {network.name}: {e}")


if __name__ == "__main__":
    cleanup(os.environ.get("TAG_NAME") or "test")
