import * as React from 'react';
import { BlockInfo } from '../grpc/io/casperlabs/casper/consensus/info_pb';
import { RefreshButton, Loading } from './Utils';
import * as d3 from 'd3';
import $ from 'jquery';
import { encodeBase16 } from '../lib/Conversions';

// https://bl.ocks.org/mapio/53fed7d84cd1812d6a6639ed7aa83868

const CircleRadius = 8;
const LineColor = '#AAA';

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
  initialized = false;

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

    // Display each validator with its own color.
    const color = d3.scaleOrdinal(d3.schemeCategory10);

    const svg = d3.select(this.ref);
    if (!this.initialized) {
      // Add the zoomable container.
      const container = svg.append('g');

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
        .attr('fill', LineColor);

      this.initialized = true;
    }

    const container = svg.select('g');

    // Clear previous contents.
    container.selectAll('g').remove();

    // See what the actual width and height is.
    const width = $(this.ref!).width()!;
    const height = $(this.ref!).height()!;

    let graph: Graph = toGraph(this.props.blocks);
    graph = calculateCoordinates(graph, width, height);

    const link = container
      .append('g')
      .attr('class', 'links')
      .selectAll('line')
      .data(graph.links)
      .enter()
      .append('line')
      .attr('stroke', LineColor)
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
      .attr('r', CircleRadius)
      .attr('stroke', '#fff')
      .attr('stroke-width', '1.5px')
      .attr('fill', (d: d3Node) => color(d.validator));

    const label = node
      .append('text')
      .text((d: d3Node) => d.title)
      .attr('x', 9)
      .attr('y', 12)
      .style('font-family', 'Arial')
      .style('font-size', 12)
      .style('pointer-events', 'none'); // to prevent mouseover/drag capture

    node
      .append('title')
      .text(
        (d: d3Node) => `Block: ${d.id} @ ${d.rank}\nValidator: ${d.validator}`
      );

    const focus = (d: any) => {
      let datum = d3.select(d3.event.target).datum() as d3Node;
      node.style('opacity', x =>
        graph.areNeighbours(x.id, datum.id) ? 1 : 0.1
      );
      label.attr('display', x =>
        graph.areNeighbours(x.id, datum.id) ? 'block' : 'none'
      );
      link.style('opacity', x =>
        x.source.id === datum.id || x.target.id === datum.id ? 1 : 0.1
      );
    };

    function unfocus() {
      label.attr('display', 'block');
      node.style('opacity', 1);
      link.style('opacity', 1);
    }

    node.on('mouseover', focus).on('mouseout', unfocus);

    const updatePositions = () => {
      link
        .attr('x1', (d: any) => d.source.x)
        .attr('y1', (d: any) => d.source.y)
        .attr(
          'x2',
          (d: any) =>
            d.source.x +
            (d.target.x - d.source.x) * shorten(d, CircleRadius + 2)
        )
        .attr(
          'y2',
          (d: any) =>
            d.source.y +
            (d.target.y - d.source.y) * shorten(d, CircleRadius + 2)
        );
      node.attr('transform', (d: any) => 'translate(' + d.x + ',' + d.y + ')');
    };

    updatePositions();
  }
}

interface d3Node {
  id: string;
  title: string;
  validator: string;
  rank: number;
  x?: number;
  y?: number;
}

interface d3Link {
  source: d3Node;
  target: d3Node;
  isMainParent: boolean;
}

class Graph {
  private targets: Map<String, Set<String>> = new Map();

  constructor(public nodes: d3Node[], public links: d3Link[]) {
    links.forEach(link => {
      let targets = this.targets.get(link.source.id) || new Set<String>();
      targets.add(link.target.id);
      this.targets.set(link.source.id, targets);
    });
  }

  hasTarget = (from: string, to: string) =>
    this.targets.has(from) && this.targets.get(from)!.has(to);

  areNeighbours = (a: string, b: string) =>
    a === b || this.hasTarget(a, b) || this.hasTarget(b, a);
}

/** Turn blocks into the reduced graph structure. */
const toGraph = (blocks: BlockInfo[]) => {
  let nodes: d3Node[] = blocks.map(block => {
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
  });

  let nodeMap = new Map(nodes.map(x => [x.id, x]));

  let links = blocks.flatMap(block => {
    let child = blockHash(block);

    let parents = block
      .getSummary()!
      .getHeader()!
      .getParentHashesList_asU8()
      .map(h => encodeBase16(h));

    return parents
      .filter(parent => nodeMap.has(parent))
      .map(parent => {
        return {
          source: nodeMap.get(child)!,
          target: nodeMap.get(parent)!,
          isMainParent: parent === parents[0]
        };
      });
  });

  return new Graph(nodes, links);
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

const blockHash = (block: BlockInfo) =>
  encodeBase16(block.getSummary()!.getBlockHash_asU8());

const validatorHash = (block: BlockInfo) =>
  encodeBase16(
    block
      .getSummary()!
      .getHeader()!
      .getValidatorPublicKey_asU8()
  );

/** Shorten lines by a fixed amount so that the line doesn't stick out from under the arrows tip. */
const shorten = (d: any, by: number) => {
  let length = Math.sqrt(
    Math.pow(d.target.x - d.source.x, 2) + Math.pow(d.target.y - d.source.y, 2)
  );
  return Math.max(0, (length - by) / length);
};
