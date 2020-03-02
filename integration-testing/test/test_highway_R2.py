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
    client = net.docker_nodes[0].p_client.client
    for block_info in client.stream_events(all=True):
        print(block_info)
