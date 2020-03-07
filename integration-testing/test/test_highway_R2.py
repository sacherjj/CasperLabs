import logging
from datetime import datetime
from collections import defaultdict
from casperlabs_client import CasperLabsClient, consensus_pb2 as consensus


def test_highway(three_node_highway_network):
    net = three_node_highway_network
    for node in net.docker_nodes:
        logs = node.logs()
        if "Highway" in logs and not ("NCB" in logs):
            logging.info(f"{node} is on Highway!")
        else:
            raise Exception(f"{node} is not on Highway")
    client = net.docker_nodes[0].p_client.client
    check_highway_dag(client)


def filter_ballots(block_infos):
    return filter(lambda b: b.summary.header.message_type == consensus.Block.MessageType.BALLOT)
    return [
        b
        for b in block_infos
        if b.summary.header.message_type == consensus.Block.MessageType.BALLOT
    ]


def filter_blocks(block_infos):
    return filter(lambda b: b.summary.header.message_type == consensus.Block.MessageType.BLOCK)
    return [
        b
        for b in block_infos
        if b.summary.header.message_type == consensus.Block.MessageType.BLOCK
    ]


def split_ballots_and_blocks(block_infos):
    return filter_ballots(block_infos), filter_blocks(block_infos)


def datetime_from_timestamp(timestamp):
    return datetime.fromtimestamp(timestamp / 1000.0)


def check_eras(blocks_in_eras, client):
    """
    :param:  key_block_hashes  Mapping from key_block_hash to nodes in the era.
    """
    key_block_hashes = list(blocks_in_eras.keys())
    for key_block_hash in key_block_hashes:
        messages = blocks_in_eras[key_block_hash]
        ballots, blocks = split_ballots_and_blocks(messages)

        assert len(blocks) > 0

        key_block = client.showBlock(key_block_hash.hex(), full_view=False)

        block_validator_public_keys = [
            b.summary.header.validator_public_key.hex() for b in blocks
        ]
        unique_validators = sorted(set(block_validator_public_keys))
        validator_frequencies = sorted(
            [(v, block_validator_public_keys.count(v)) for v in unique_validators],
            key=lambda p: p[1],
            reverse=True,
        )
        logging.info(
            f"""key_block_hash: {key_block_hash.hex()} ({datetime_from_timestamp(key_block.summary.header.timestamp)}):
            {len(blocks)} blocks ({len(blocks)}), {len(ballots)} ballots
            {", ".join(f"{v}: {f}" for v,f in validator_frequencies)}
        """
        )


def check_rounds(blocks_in_rounds):
    # Skip the first and the last round.
    round_ids = sorted(list(blocks_in_rounds.keys()))[1:-1]
    for round_id in round_ids:
        ballots, blocks = split_ballots_and_blocks(blocks_in_rounds[round_id])

        # There must be at most one block in a round.
        assert len(blocks) <= 1

        validator_public_keys = [
            b.summary.header.validator_public_key.hex()[:10] for b in blocks
        ]
        logging.info(
            f"round_id: {round_id} ({datetime_from_timestamp(round_id)}): {len(blocks)} blocks ({validator_public_keys}), {len(ballots)} ballots"
        )


def check_highway_dag(client, number_of_rounds=1):
    blocks_in_rounds = defaultdict(list)
    blocks_in_eras = defaultdict(list)
    for event in client.stream_events(block_added=True):
        # print(event)
        # import pdb; pdb.set_trace()
        block_info = event.block_added.block
        round_id = block_info.summary.header.round_id
        key_block_hash = block_info.summary.header.key_block_hash

        blocks_in_rounds[round_id].append(block_info)
        if key_block_hash:
            blocks_in_eras[key_block_hash].append(block_info)

        if len(blocks_in_eras.keys()) > number_of_rounds:
            check_eras(blocks_in_eras, client)
            check_rounds(blocks_in_rounds)
            break


if __name__ == "__main__":
    client = CasperLabsClient()
    check_highway_dag(client)
