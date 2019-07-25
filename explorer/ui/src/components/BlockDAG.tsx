import * as React from 'react';
import { BlockInfo } from '../grpc/io/casperlabs/casper/consensus/info_pb';
import { RefreshButton, Loading } from './Utils';
import * as d3 from 'd3';
import $ from 'jquery';
import { encodeBase16 } from '../lib/Conversions';

// https://bl.ocks.org/mapio/53fed7d84cd1812d6a6639ed7aa83868

export interface Props {
  title: string;
  refresh?: () => void;
  blocks: BlockInfo[] | null;
  emptyMessage?: any;
  footerMessage?: any;
  width: string | number;
  height: string | number;
}

export class BlockDAG extends React.Component<Props, {}> {
  ref: SVGSVGElement | null = null;

  render() {
    return (
      <div className="card mb-3">
        <div className="card-header">
          <span>{this.props.title}</span>
          {this.props.refresh && (
            <div className="float-right">
              <RefreshButton refresh={() => this.props.refresh!()} />
            </div>
          )}
        </div>
        <div className="card-body">
          {this.props.blocks == null ? (
            <Loading />
          ) : this.props.blocks.length === 0 ? (
            <div className="small text-muted">
              {this.props.emptyMessage || 'No blocks to show.'}
            </div>
          ) : (
            <svg
              width={this.props.width}
              height={this.props.height}
              ref={(ref: SVGSVGElement) => (this.ref = ref)}
            >
              {' '}
            </svg>
          )}
        </div>
        {this.props.footerMessage && (
          <div className="card-footer small text-muted">
            {this.props.footerMessage}
          </div>
        )}
      </div>
    );
  }

  componentDidUpdate() {
    if (this.props.blocks == null || this.props.blocks.length === 0) return;

    const color = d3.scaleOrdinal(d3.schemeCategory10);
    const circleRadius = 5;
    const lineColor = '#AAA';

    const svg = d3.select(this.ref);
    const container = svg.append('g');

    // See what the actual width and height is.
    const width = $(this.ref!).width()!;
    const height = $(this.ref!).height()!;

    let graph: Graph = toGraph(this.props.blocks);
    graph = calculateCoordinates(graph, width, height);
    graph = connectLinks(graph);

    const zoom: any = d3
      .zoom()
      .scaleExtent([0.1, 4])
      .on('zoom', () => container.attr('transform', d3.event.transform));
    svg.call(zoom);

    // Draw an arrow at the end of the lines to point at parents.
    svg
      .append('svg:defs')
      .append('svg:marker')
      .attr('id', 'arrow')
      .attr('refX', 6)
      .attr('refY', 6)
      .attr('markerWidth', 10)
      .attr('markerHeight', 10)
      .attr('orient', 'auto')
      .append('path')
      .attr('d', 'M 3 4 7 6 3 8')
      .attr('fill', lineColor);

    const link = container
      .append('g')
      .attr('class', 'links')
      .selectAll('line')
      .data(graph.links)
      .enter()
      .append('line')
      .attr('stroke', lineColor)
      .attr('stroke-width', (d: d3Link) => (d.isMainParent ? 2 : 1))
      .attr('marker-end', 'url(#arrow)');

    const node = container
      .append('g')
      .attr('class', 'nodes')
      .selectAll('g')
      .data(graph.nodes)
      .enter()
      .append('g');

    node
      .append('circle')
      .attr('r', circleRadius)
      .attr('stroke', '#fff')
      .attr('stroke-width', '1.5px')
      .attr('fill', (d: d3Node) => color(d.validator));

    node
      .append('text')
      .text((d: d3Node) => d.title)
      .attr('x', 6)
      .attr('y', 12)
      .style('font-family', 'Arial')
      .style('font-size', 12)
      .style('pointer-events', 'none'); // to prevent mouseover/drag capture

    node
      .append('title')
      .text(
        (d: d3Node) => `Block: ${d.id} @ ${d.rank}\nValidator: ${d.validator}`
      );

    const ticked = () => {
      link
        .attr('x1', (d: any) => d.source.x)
        .attr('y1', (d: any) => d.source.y)
        .attr(
          'x2',
          (d: any) =>
            d.source.x +
            (d.target.x - d.source.x) * shorten(d, circleRadius + 2)
        )
        .attr(
          'y2',
          (d: any) =>
            d.source.y +
            (d.target.y - d.source.y) * shorten(d, circleRadius + 2)
        );
      node.attr('transform', (d: any) => 'translate(' + d.x + ',' + d.y + ')');
    };

    ticked();
  }
}

/** Shorten lines by a fixed amount so that the line doesn't stick out from under the arrows tip. */
const shorten = (d: any, by: number) => {
  let length = Math.sqrt(
    Math.pow(d.target.x - d.source.x, 2) + Math.pow(d.target.y - d.source.y, 2)
  );
  return Math.max(0, (length - by) / length);
};

/** Turn blocks into the reduced graph structure. */
const toGraph = (blocks: BlockInfo[]) => {
  let graph: Graph = {
    nodes: blocks.map(block => {
      let id = blockHash(block);
      return {
        id: id,
        title: id.substr(0, 10) + '...',
        validator: validatorHash(block),
        rank: block
          .getSummary()!
          .getHeader()!
          .getRank()
      };
    }),
    links: blocks.flatMap(block => {
      let child = blockHash(block);
      let parents = block
        .getSummary()!
        .getHeader()!
        .getParentHashesList_asU8()
        .map(h => encodeBase16(h));
      return parents.map(parent => {
        return {
          source: child,
          target: parent,
          isMainParent: parent === parents[0]
        };
      });
    })
  };

  graph = {
    nodes: [
      { id: 'genesis', title: 'genesis...', validator: '', rank: 0 },
      {
        id: 'abcdefghij1234567890',
        title: 'abcdefghij...',
        validator: 'validator-1',
        rank: 1
      },
      {
        id: 'bcdefghijk1234567890',
        title: 'bcdefghijk...',
        validator: 'validator-2',
        rank: 1
      },
      {
        id: 'cdefghijkl1234567890',
        title: 'cdefghijkl...',
        validator: 'validator-1',
        rank: 2
      },
      {
        id: 'defghijlmn1234567890',
        title: 'defghijlmn...',
        validator: 'validator-3',
        rank: 3
      }
    ],
    links: [
      { source: 'abcdefghij1234567890', target: 'genesis', isMainParent: true },
      { source: 'bcdefghijk1234567890', target: 'genesis', isMainParent: true },
      {
        source: 'cdefghijkl1234567890',
        target: 'abcdefghij1234567890',
        isMainParent: true
      },
      {
        source: 'cdefghijkl1234567890',
        target: 'bcdefghijk1234567890',
        isMainParent: false
      },
      {
        source: 'defghijlmn1234567890',
        target: 'cdefghijkl1234567890',
        isMainParent: true
      }
    ]
  };

  return graph;
};

/** Calculate coordinates so that valiators are in horizontal swimlanes, time flowing left to right. */
const calculateCoordinates = (graph: Graph, width: number, height: number) => {
  const validators = [...new Set(graph.nodes.map(x => x.validator))].sort();
  const verticalStep = height / (validators.length + 1);
  const maxRank = Math.max(...graph.nodes.map(x => x.rank));
  const minRank = Math.min(...graph.nodes.map(x => x.rank));
  const horizontalStep = width / (maxRank - minRank + 2);

  graph.nodes.forEach(node => {
    node.y = (validators.indexOf(node.validator) + 1) * verticalStep;
    node.x = (node.rank - minRank + 1) * horizontalStep;
  });

  return graph;
};

/** Replace string `id` in links with nodes. */
const connectLinks = (graph: Graph) => {
  const nodes = new Map(graph.nodes.map(x => [x.id, x]));
  graph.links.forEach(link => {
    if (typeof link.source === 'string') link.source = nodes.get(link.source)!;
    if (typeof link.target === 'string') link.target = nodes.get(link.target)!;
  });
  return graph;
};

const blockHash = (block: BlockInfo) =>
  encodeBase16(block.getSummary()!.getBlockHash_asU8());

const validatorHash = (block: BlockInfo) =>
  encodeBase16(
    block
      .getSummary()!
      .getHeader()!
      .getValidatorPublicKey_asU8()
  );

interface d3Node extends d3.SimulationNodeDatum {
  id: string;
  title: string;
  validator: string;
  rank: number;
}

interface d3Link extends d3.SimulationLinkDatum<d3Node> {
  source: string | d3Node;
  target: string | d3Node;
  isMainParent: boolean;
}

interface Graph {
  nodes: d3Node[];
  links: d3Link[];
}
