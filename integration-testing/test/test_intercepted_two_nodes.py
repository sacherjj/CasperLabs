import logging

# from casperlabs_local_net.cli import CLI, DockerCLI, CLIErrorExit


def test_python_proxy(intercepted_two_node_network):
    nodes = intercepted_two_node_network.docker_nodes
    blocks = list(nodes[0].d_client.show_blocks(1))
    logging.info(str(blocks[0]))
