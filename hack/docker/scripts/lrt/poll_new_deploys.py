#!/usr/bin/env python
from google.protobuf.json_format import MessageToDict
from dramatiq import actor
from erc20 import Node
import base64
import time

NODE = Node("localhost")


def b64_to_hex(s):
    return base64.b64decode(s).hex()


@actor
def new_deploy_processed(deploy_info: dict):
    deploy_hash = b64_to_hex(deploy_info["deploy"]["deployHash"])
    print(f"new_deploy_processed: {deploy_hash}")


@actor
def new_block(block_info: dict):
    block_hash = b64_to_hex(block_info["summary"]["blockHash"])
    print(f"New block: {block_hash}")
    for deploy_info in NODE.client.showDeploys(block_hash):
        new_deploy_processed(MessageToDict(deploy_info))


def poll_new_blocks():
    seen = set()
    while True:
        # It is not clear how to efficiently check
        # for new blocks with the current node API.
        # Ideally, we would say something like:
        # "Give me blocks newer than <block_hash>"
        for block_info in NODE.client.showBlocks(100):
            block_hash = block_info.summary.block_hash.hex()
            if block_hash not in seen:
                # Convert protobuf object to dictionary because
                # parameters passed to actors must be JSON serializable.
                new_block(MessageToDict(block_info))
                seen.add(block_hash)
        time.sleep(0.1)


if __name__ == "__main__":
    poll_new_blocks()
