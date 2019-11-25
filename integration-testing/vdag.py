from collections import defaultdict
import casperlabs_client

# ~/CasperLabs/protobuf/io/casperlabs/node/api/casper.proto
# ~/CasperLabs/protobuf/io/casperlabs/casper/consensus/info.proto
# ~/CasperLabs/protobuf/io/casperlabs/casper/consensus/consensus.proto


# Helpers for DOT language generation

INVISIBLE = "invis"  # "dashed" #"invis"


def attributes(**kwargs):
    if not kwargs:
        return ""
    return "[" + " ".join(f"{name}={value}" for (name, value) in kwargs.items()) + "]"


def node(node_id, **kwargs):
    return f'    "{node_id}" {attributes(**kwargs)}'


def edge(l1, l2, **kwargs):
    return f'    "{l1}" -> "{l2}" {attributes(**kwargs)}'


def subgraph(*args, **kwargs):
    style = kwargs.get("style", "") and f"""style="{kwargs['style']}" """
    label = kwargs.get("label", "") and f"""label="{kwargs['label']}" """
    return f"""  subgraph "{kwargs['name']}" {{
    {label}
    {style}
{cat(*args)}
  }}
"""


def graph(*args):
    return f"""
digraph "dag" {{
  rankdir=BT
  node [width=0 height=0 margin=0.03 fontsize=8 shape=box]
  splines=false
{cat(*args)}
}}
"""


def cat(*args):
    return "\n".join(type(arg) == list and cat(*arg) or str(arg) for arg in args)


# Protobuf BlockInfo abstraction/shortcuts.


def justifications(block_info):
    return [j.hex() for j in block_info.summary.header.justifications]


def rank(block_info):
    return block_info.summary.header.rank


def parents(block_info):
    ps = [h.hex() for h in block_info.summary.header.parent_hashes]
    if len(ps) == 0:
        return [""]
    return ps


def block_hash(block_info):
    return block_info.summary.block_hash.hex()


def short_hash(s: str) -> str:
    return f"{s[:10]}..."


def block_id(block_info) -> str:
    return short_hash(block_hash(block_info))


# DAG diagram construction.


def lane_alignment(block_infos, validator_id, min_rank, max_rank):
    """
    Create invisible edges between nodes in a lane to keep them rendered in order of their ranks.
    Create invisible nodes for missing ranks.
    """
    ranks = set(rank(b) for b in block_infos)
    edges = []
    nodes = []

    block_ids_by_rank = defaultdict(list)
    for b in block_infos:
        block_ids_by_rank[rank(b)].append(block_id(b))

    def dummy_id(i):
        return f"{i}_{validator_id}"

    for i in range(min_rank, max_rank):
        if i not in ranks and not block_ids_by_rank[i]:
            block_ids_by_rank[i] = [dummy_id(i)]
            nodes.append(node(block_ids_by_rank[i][0], style=INVISIBLE))

        if i + 1 not in ranks:
            block_ids_by_rank[i + 1] = [dummy_id(i + 1)]
            nodes.append(node(block_ids_by_rank[i + 1][0], style=INVISIBLE))

        edges.extend(
            [
                edge(n1, n2, style=INVISIBLE)
                for n1 in block_ids_by_rank[i]
                for n2 in block_ids_by_rank[i + 1]
            ]
        )

    return block_ids_by_rank, [nodes, edges]


def lane(validator, block_infos, min_rank, max_rank, genesis_block_id):
    validator_id = short_hash(validator)
    nodes = [node(block_id(b)) for b in block_infos]
    block_ids_by_rank, alignment_in_lane = lane_alignment(
        block_infos, validator_id, min_rank, max_rank
    )
    return subgraph(
        nodes,
        # lanes_alignment,
        alignment_in_lane,
        name=f"cluster_{validator_id}",
        label=f"{validator_id}",
    )


def generate_dot(block_infos):
    genesis_block_id = "genesis_block"
    validator_blocks = defaultdict(list)
    for b in block_infos:
        pk = b.summary.header.validator_public_key
        if len(pk):
            validator_blocks[pk.hex()].append(b)
        else:
            genesis_block_id = block_id(b)

    last_finalized_block_hash = next(
        (block_hash(b) for b in block_infos if b.status.fault_tolerance > 0), ""
    )

    last_finalized_block_hash = last_finalized_block_hash

    ranks = set(rank(b) for b in block_infos)
    min_rank = min(ranks)
    max_rank = max(ranks)
    lanes = [
        lane(validator, block_infos, min_rank, max_rank, genesis_block_id)
        for (validator, block_infos) in validator_blocks.items()
    ]

    parent_edges = [
        [
            edge(
                block_id(b), short_hash(parents(b)[0]), style="bold", constraint="false"
            )
            for b in block_infos
        ]
        for (validator, block_infos) in validator_blocks.items()
    ]

    lanes_alignment = [
        edge(genesis_block_id, alignment_node_id, style=INVISIBLE)
        for (validator, block_infos) in validator_blocks.items()
        for alignment_node_id in lane_alignment(
            block_infos, short_hash(validator), min_rank, max_rank
        )[0][min_rank]
    ]

    genesis_block = (
        genesis_block_id != "genesis_block"
        and node(genesis_block_id)
        or node(genesis_block_id, style=INVISIBLE)
    )

    return graph(genesis_block, lanes_alignment, lanes, parent_edges)


def main():
    client = casperlabs_client.CasperLabsClient("deploy.casperlabs.io")
    # client = casperlabs_client.CasperLabsClient("localhost", port=40411, port_internal=40412)
    block_infos = sorted(client.showBlocks(depth=10), key=rank)

    print(generate_dot(block_infos))


main()
