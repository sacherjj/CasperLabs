import logging


def test_highway(three_node_highway_network):
    net = three_node_highway_network
    logging.info("On the highway...")
    for node in net.docker_nodes:
        logs = node.logs()
        if "Highway" in logs and not ("NCB" in logs):
            logging.info(f"{node} is on Highway!")
        else:
            raise Exception(f"{node} is not on Highway")
