import logging
from datetime import datetime
from collections import defaultdict
from casperlabs_client import consensus_pb2 as consensus


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
    check_highway_dag(client)


def filter_ballots(block_infos):
    return [
        b
        for b in block_infos
        if b.summary.header.message_type == consensus.Block.MessageType.BALLOT
    ]


def filter_blocks(block_infos):
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
    for key_block_hash in blocks_in_eras:
        messages = blocks_in_eras[key_block_hash]
        ballots, blocks = split_ballots_and_blocks(messages)

        # import pdb; pdb.set_trace()
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
        print(
            f"""key_block_hash: {key_block_hash.hex()} ({datetime_from_timestamp(key_block.summary.header.timestamp)}):
            {len(blocks)} blocks ({len(blocks)}), {len(ballots)} ballots
            {", ".join(f"{v}: {f}" for v,f in validator_frequencies)}
        """
        )


def check_round(blocks_in_rounds):
    for round_id in blocks_in_rounds:
        ballots, blocks = split_ballots_and_blocks(blocks_in_rounds[round_id])
        validator_public_keys = [
            b.summary.header.validator_public_key.hex()[:10] for b in blocks
        ]
        print(
            f"{round_id} ({datetime_from_timestamp(round_id)}): {len(blocks)} blocks ({validator_public_keys}), {len(ballots)} ballots"
        )


def check_highway_dag(client):
    blocks_in_rounds = defaultdict(list)
    blocks_in_eras = defaultdict(list)
    for event in client.stream_events(all=True):
        # 'deploy_added', 'deploy_discarded', 'deploy_finalized', 'deploy_orphaned', 'deploy_processed', 'deploy_requeued', 'new_finalized_block'
        if event.HasField("block_added"):
            # print(event)
            # import pdb; pdb.set_trace()
            block_info = event.block_added.block
            blocks_in_rounds[block_info.summary.header.round_id].append(block_info)
            blocks_in_eras[block_info.summary.header.key_block_hash].append(block_info)

        if len(blocks_in_eras.keys()) > 4:
            check_eras(blocks_in_eras, client)
            check_round(blocks_in_rounds)
            break


if __name__ == "__main__":
    import casperlabs_client

    client = casperlabs_client.CasperLabsClient()
    check_highway_dag(client)
