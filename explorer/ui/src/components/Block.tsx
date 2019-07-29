import React from 'react';
import { RouteComponentProps, withRouter, Link } from 'react-router-dom';
import { observer } from 'mobx-react';
import {
  BlockContainer,
  BlockContainerFactory
} from '../containers/BlockContainer';
import { decodeBase16, encodeBase16 } from '../lib/Conversions';
import DataTable from './DataTable';
import { BlockInfo } from '../grpc/io/casperlabs/casper/consensus/info_pb';
import Pages from './Pages';
import { RefreshableComponent } from './Utils';
import { BlockDAG } from './BlockDAG';

// https://www.pluralsight.com/guides/react-router-typescript

// URL parameter
type Params = {
  blockHashBase16: string;
};

interface Props extends RouteComponentProps<Params> {
  blocks: BlockContainerFactory;
}

@observer
class _Block extends RefreshableComponent<Props, {}> {
  container: BlockContainer;

  constructor(props: Props) {
    super(props);
    this.container = this.props.blocks.make();
    this.container.init(decodeBase16(this.blockHashBase16));
  }

  get blockHashBase16() {
    return this.props.match.params.blockHashBase16;
  }

  async refresh() {
    await this.container.loadBlock();
    await this.container.loadNeighborhood();
  }

  componentDidUpdate() {
    // Container and component stays the same during naviagation.
    if (this.blockHashBase16 !== this.container.blockHashBase16) {
      this.container.init(decodeBase16(this.blockHashBase16));
      this.refresh();
    }
  }

  render() {
    return (
      <div>
        <BlockTable
          blockHashBase16={this.blockHashBase16}
          block={this.container.block}
          refresh={() => this.refresh()}
        />
        {this.container.neighborhood && (
          <BlockDAG
            title={`Neigborhood of block ${this.blockHashBase16}`}
            refresh={() => this.container.loadNeighborhood()}
            blocks={this.container.neighborhood}
            width="100%"
            height="400px"
            selected={this.container.block!}
            depth={this.container.depth}
            onSelected={block => {
              let target = encodeBase16(
                block.getSummary()!.getBlockHash_asU8()
              );
              if (target !== this.blockHashBase16) {
                this.props.history.push(Pages.block(target));
              }
            }}
          />
        )}
      </div>
    );
  }
}

const BlockTable = observer(
  (props: {
    blockHashBase16: string;
    block: BlockInfo | null;
    refresh: () => void;
  }) => {
    const attrs = props.block && blockAttrs(props.block);
    return (
      <DataTable
        title={`Block ${props.blockHashBase16}`}
        headers={[]}
        rows={attrs}
        renderRow={(attr, i) => (
          <tr key={i}>
            <th>{attr[0]}</th>
            <td>{attr[1]}</td>
          </tr>
        )}
        refresh={() => props.refresh()}
      />
    );
  }
);

const blockAttrs: (block: BlockInfo) => Array<[string, any]> = (
  block: BlockInfo
) => {
  const id = encodeBase16(block.getSummary()!.getBlockHash_asU8());
  const header = block.getSummary()!.getHeader()!;
  const validatorId = encodeBase16(header.getValidatorPublicKey_asU8());
  return [
    ['Block hash', id],
    ['Rank', header.getRank()],
    ['Timestamp', new Date(header.getTimestamp()).toISOString()],
    [
      'Parents',
      <ul>
        {header.getParentHashesList_asU8().map((x, idx) => (
          <li key={idx}>
            <BlockLink blockHash={x} />
          </li>
        ))}
      </ul>
    ],
    ['Validator', validatorId],
    ['Validator block number', header.getValidatorBlockSeqNum()],
    [
      'Validator stake',
      (() => {
        let validatorBond = header
          .getState()!
          .getBondsList()
          .find(
            x => encodeBase16(x.getValidatorPublicKey_asU8()) === validatorId
          );
        // Genesis doesn't have a validator.
        return (
          (validatorBond && validatorBond.getStake().toLocaleString()) || null
        );
      })()
    ],
    ['Deploy count', header.getDeployCount()],
    [
      'Deploy error count',
      block
        .getStatus()!
        .getStats()!
        .getDeployErrorCount()
    ],
    [
      'Block size (bytes)',
      block
        .getStatus()!
        .getStats()!
        .getBlockSizeBytes()
        .toLocaleString()
    ],
    [
      'Fault tolerance',
      block
        .getStatus()!
        .getFaultTolerance()
        .toFixed(3)
    ]
  ];
};

export const Block = withRouter(_Block);

const BlockLink = (props: { blockHash: ByteArray }) => {
  let id = encodeBase16(props.blockHash);
  return <Link to={Pages.block(id)}>{id}</Link>;
};

export default Block;
