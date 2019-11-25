from collections import defaultdict
import casperlabs_client

# ~/CasperLabs/protobuf/io/casperlabs/node/api/casper.proto
# ~/CasperLabs/protobuf/io/casperlabs/casper/consensus/info.proto
# ~/CasperLabs/protobuf/io/casperlabs/casper/consensus/consensus.proto


# Helpers for DOT language generation

INVISIBLE = "invis"


def attributes(**kwargs):
    if not kwargs:
        return ""
    return "[" + " ".join(f"{name}={value}" for (name, value) in kwargs.items()) + "]"


def node(label, **kwargs):
    return f'    "{label}" {attributes(**kwargs)}'


def edge(l1, l2, **kwargs):
    return f'    "{l1}" -> "{l2}" {attributes(**kwargs)}'


def subgraph(*args, name=None, label=None):
    return f"""  subgraph "{name}" {{
    label = "{label}"
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


def alignment(block_infos, validator_id, min_rank, max_rank):
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

    for i in range(min_rank, max_rank):
        if i in ranks:
            n1s = block_ids_by_rank[i]
        else:
            n1s = [f"{i}_{validator_id}"]
            nodes.append(node(n1s[0], style=INVISIBLE))

        if i + 1 in ranks:
            n2s = block_ids_by_rank[i + 1]
        else:
            n2s = [f"{i+1}_{validator_id}"]
            nodes.append(node(n2s[0], style=INVISIBLE))
        for n1 in n1s:
            for n2 in n2s:
                edges.append(edge(n1, n2, style=INVISIBLE))

    # import pdb; pdb.set_trace()
    return [nodes, edges]


def lane(validator, block_infos, min_rank, max_rank):
    validator_id = short_hash(validator)
    nodes = [node(block_id(b)) for b in block_infos]
    edges = [
        edge(block_id(b), short_hash(parents(b)[0]), style="bold", constraint="false")
        for b in block_infos
    ]
    return subgraph(
        nodes,
        edges,
        alignment(block_infos, validator_id, min_rank, max_rank),
        name=f"cluster_{validator_id}",
        label=f"{validator_id}",
    )


def plot(block_infos):
    genesis_block_id = None
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
        lane(validator, block_infos, min_rank, max_rank)
        for (validator, block_infos) in validator_blocks.items()
    ]

    maybe_genesis_block_subgraph = (
        genesis_block_id
        and subgraph(node(genesis_block_id), name="cluster_genesis", label="genesis")
        or ""
    )

    return graph(maybe_genesis_block_subgraph, lanes)


def main():
    # client = casperlabs_client.CasperLabsClient("deploy.casperlabs.io")
    client = casperlabs_client.CasperLabsClient(
        "localhost", port=40411, port_internal=40412
    )
    block_infos = sorted(client.showBlocks(depth=10), key=rank)

    print(plot(block_infos))


main()
