#!/usr/bin/env python
from google.protobuf.json_format import MessageToDict
from dramatiq import actor
from erc20 import Node
import base64

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
    for event in NODE.client.stream_events():
        if event.HasField("block_added"):
            block_info = event.block_added.block
            new_block(MessageToDict(block_info))


if __name__ == "__main__":
    poll_new_blocks()
