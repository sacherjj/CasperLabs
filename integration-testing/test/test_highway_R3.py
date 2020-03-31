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
    check_highway_dag(client, len(net.docker_nodes))


def filter_ballots(block_infos):
    return filter(
        lambda b: b.summary.header.message_type == consensus.Block.MessageType.BALLOT,
        block_infos,
    )


def filter_blocks(block_infos):
    return filter(
        lambda b: b.summary.header.message_type == consensus.Block.MessageType.BLOCK,
        block_infos,
    )


def split_ballots_and_blocks(block_infos):
    return map(list, (filter_ballots(block_infos), filter_blocks(block_infos)))


def datetime_from_timestamp(timestamp):
    return datetime.fromtimestamp(timestamp / 1000.0)


def log_info(s):
    logging.info(s)


def check_eras(blocks_in_eras, client):
    """
    :param:  blocks_in_eras  Mapping from key_block_hash to nodes in the era.
    """
    key_block_hashes = list(blocks_in_eras.keys())
    for key_block_hash in key_block_hashes:
        messages = blocks_in_eras[key_block_hash]
        ballots, blocks = split_ballots_and_blocks(messages)

        assert len(blocks) > 0, "There should be at least one block in each era"

        key_block = client.showBlock(key_block_hash.hex(), full_view=False)

        block_validator_public_keys = [validator_id(b) for b in blocks]
        unique_validators = sorted(set(block_validator_public_keys))
        validator_frequencies = sorted(
            [(v, block_validator_public_keys.count(v)) for v in unique_validators],
            key=lambda p: p[1],
            reverse=True,
        )
        log_info(
            f"""key_block_hash: {key_block_hash.hex()} ({datetime_from_timestamp(key_block.summary.header.timestamp)}): {len(blocks)} blocks ({len(blocks)}), {len(ballots)} ballots
            {format_list(f"{v}: {f}" for v,f in validator_frequencies)}
        """
        )


def validator_id(block_info):
    return block_info.summary.header.validator_public_key.hex()


def validator_id_short(block_info):
    return validator_id(block_info)[:10]


def format_list(l):
    return ",".join(l)


def plural(singular_name, l):
    return len(list(l)) == 1 and singular_name or singular_name + "s"


def check_rounds(blocks_in_rounds):
    # Skip the last round, it may be not full/finished.
    for round_id, key_block_hash in sorted(list(blocks_in_rounds.keys()))[:-1]:
        ballots, blocks = split_ballots_and_blocks(
            blocks_in_rounds[(round_id, key_block_hash)]
        )

        validator_public_keys = map(validator_id_short, blocks)
        log_info(
            f"""round_id: {round_id} key_block_hash: {key_block_hash.hex()[:10]} ({datetime_from_timestamp(round_id)}): {len(blocks)} {plural("block", blocks)} ({format_list(validator_public_keys)})), {len(ballots)} {plural("ballot", ballots)}"""
        )

        assert len(blocks) <= 1, "There must be at most one block in a round"


def check_highway_dag(client, number_of_validators, number_of_eras=3):
    blocks_in_rounds = defaultdict(list)
    blocks_in_eras = defaultdict(list)
    for event in client.stream_events(block_added=True):
        block_info = event.block_added.block
        block_hash = block_info.summary.block_hash

        log_info(
            f"Block added: {block_hash.hex()}, validator: {validator_id_short(block_info)}"
        )
        round_id = block_info.summary.header.round_id
        key_block_hash = block_info.summary.header.key_block_hash

        blocks_in_rounds[(round_id, key_block_hash)].append(block_info)
        if key_block_hash:
            blocks_in_eras[key_block_hash].append(block_info)

        full_eras = {
            k: blocks_in_eras[k]
            for k in blocks_in_eras
            if len(blocks_in_eras[k]) >= 2 * number_of_validators
        }
        if len(full_eras) > number_of_eras:
            check_eras(full_eras, client)
            check_rounds(blocks_in_rounds)
            break


if __name__ == "__main__":
    client = CasperLabsClient()
    check_highway_dag(client, len(client.show_peers()) + 1)
