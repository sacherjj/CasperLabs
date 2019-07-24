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
    const svg = d3.select(this.ref);
    const container = svg.append('g');

    // See what the actual width and height is.
    const width = $(this.ref!).width()!;
    const height = $(this.ref!).height()!;

    const circleRadius = 5;
    const lineColor = '#AAA';

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

    const graph: Graph = toGraph(this.props.blocks);

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
      .attr('fill', (d: d3Node) => color(d.group));

    node
      .append('text')
      .text((d: d3Node) => d.title)
      .attr('x', 6)
      .attr('y', 3)
      .style('font-family', 'Arial')
      .style('font-size', 12)
      .style('pointer-events', 'none'); // to prevent mouseover/drag capture

    node.append('title').text((d: d3Node) => d.id);

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

    d3.forceSimulation(graph.nodes)
      .force('charge', d3.forceManyBody().strength(-3000))
      .force('center', d3.forceCenter(width / 2, height / 2))
      .force('x', d3.forceX(width / 2).strength(1))
      .force('y', d3.forceY(height / 2).strength(1))
      .force(
        'link',
        d3
          .forceLink<d3Node, d3Link>(graph.links)
          .id(x => x.id)
          .distance(50)
          .strength(1)
      )
      .on('tick', ticked);

    d3.forceSimulation(graph.nodes)
      .force('charge', d3.forceManyBody().strength(-50))
      .force(
        'link',
        d3
          .forceLink(graph.links)
          .distance(0)
          .strength(2)
      );
  }
}

// Shorten lines by a fixed amount so that the line doesn't stick out from under the arrows tip.
const shorten = (d: any, by: number) => {
  let length = Math.sqrt(
    Math.pow(d.target.x - d.source.x, 2) + Math.pow(d.target.y - d.source.y, 2)
  );
  return Math.max(0, (length - by) / length);
};

const toGraph = (blocks: BlockInfo[]) => {
  let graph: Graph = {
    nodes: blocks.map(block => {
      let id = blockHash(block);
      return {
        id: id,
        title: id.substr(0, 10) + '...',
        group: validatorHash(block)
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
      { id: 'genesis', title: 'genesis...', group: '' },
      {
        id: 'abcdefghij1234567890',
        title: 'abcdefghij...',
        group: 'validator-1'
      },
      {
        id: 'bcdefghijk1234567890',
        title: 'bcdefghijk...',
        group: 'validator-2'
      },
      {
        id: 'cdefghijkl1234567890',
        title: 'cdefghijkl...',
        group: 'validator-1'
      },
      {
        id: 'defghijlmn1234567890',
        title: 'defghijlmn...',
        group: 'validator-3'
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
  group: string;
}

interface d3Link extends d3.SimulationLinkDatum<d3Node> {
  source: string;
  target: string;
  isMainParent: boolean;
}

interface Graph {
  nodes: d3Node[];
  links: d3Link[];
}
