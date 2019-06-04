import os
import docker

from docker.errors import APIError


def cleanup(tag_name: str):
    dc = docker.client.from_env()
    for container in dc.containers.list(all=True, sparse=True):
        container.reload()
        if container.name:
            # Don't remove testing image that we may be running in.
            if 'integration-testing' in container.name:
                continue
            if tag_name in container.name:
                try:
                    print(f'REMOVING ABANDONED DOCKER CONTAINER: {container.name}')
                    container.remove(force=True)
                except APIError as e:
                    print(f'Error removing container {container.name}: {e}')
    for network in dc.networks.list():
        if tag_name in network.name:
            try:
                print(f'REMOVING ABANDONED DOCKER NETWORK: {network.name}')
                network.remove()
            except APIError as e:
                print(f'Error removing network {network.name}: {e}')


if __name__ == '__main__':
    cleanup(os.environ.get("TAG_NAME") or 'test')
