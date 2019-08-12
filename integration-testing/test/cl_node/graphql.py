import requests
import json
import logging


class GraphQL:
    """
    Simple wrapper for calling into Node with GraphQL queries
    """

    DEFAULT_BLOCK_SUB_SELECT = """blockHash
                            parentHashes
                            deployCount
                            deployErrorCount
                            blockSizeBytes"""
    DEFAULT_DEPLOY_SUB_SELECT = """deploy {
                                deployHash
                                accountId
                                timestamp
                                gasPrice
                            }
                            processingResults {
                                block {
                                    blockHash
                                }
                                cost
                                isError
                                errorMessage
                            }"""

    def __init__(self, node):
        self.node = node

    @property
    def url(self) -> str:
        server = self.node.container_name if self.node.is_in_docker else "0.0.0.0"
        return f"http://{server}:{self.node.http_port}/graphql"

    def query(self, query_json: dict) -> dict:
        logging.info(f"GraphQL Query to {self.url} with {query_json}")
        r = requests.post(url=self.url, json=query_json)
        return json.loads(r.text)

    def query_block(self, block_hash_prefix_hex: str, sub_select: str = None) -> dict:
        """
        Wrapper for querying a block.

        Available subselect fields

            deploys
            blockHash
            parentHashes
            justificationHashes
            timestamp
            protocolVersion
            deployCount
            rank
            validatorPublicKey
            validatorBlockSeqNum
            chainId
            signature
            faultTolerance
            blockSizeBytes
            deployErrorCount

        :param block_hash_prefix_hex: Base 16 block hash
        :param sub_select: sub select fields, using DEFAULT_BLOCK_SUB_SELECT is omitted
        :return: dict of response
        """
        if sub_select is None:
            sub_select = self.DEFAULT_BLOCK_SUB_SELECT
        q_json = {
            "query": f"""{{
                        block(blockHashBase16Prefix: "{block_hash_prefix_hex}")
                        {{ {sub_select} }}
                      }}"""
        }
        return self.query(q_json)

    def query_deploy(self, deploy_hash_hex: str, sub_select: str = None) -> dict:
        """
        Wrapper for querying deploy

        sub select can be complex, see graphql scheme.

        :param deploy_hash_hex: Base 16 block hash
        :param sub_select: sub select fields, using DEFAULT_DEPLOY_SUB_SELECT if omitted
        :return: dict of response
        """
        if sub_select is None:
            sub_select = self.DEFAULT_DEPLOY_SUB_SELECT
        q_json = {
            "query": f"""{{ deploy(deployHashBase16: "{deploy_hash_hex}")
                        {{ {sub_select} }}
                      }}"""
        }
        return self.query(q_json)

    # TODO: Figure out proper ValueUnion! type to use for sub query to make query_global_state
