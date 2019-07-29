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
    const blockHash = decodeBase16(this.blockHashBase16);
    this.container = this.props.blocks.init(blockHash);
  }

  get blockHashBase16() {
    return this.props.match.params.blockHashBase16;
  }

  async refresh() {
    await this.container.loadBlock();
  }

  render() {
    const attrs: Array<Array<[string, any]>> | null =
      this.container.block == null
        ? null
        : this.blockAttrs(this.container.block);

    return (
      <DataTable
        title={`Block ${this.blockHashBase16}`}
        headers={[]}
        rows={attrs}
        renderRow={(attr, i) => (
          <tr key={i}>
            <th>{attr[0]}</th>
            <td>{attr[1]}</td>
          </tr>
        )}
      />
    );
  }

  // Grouped attributes so we could display 2 sets of fields next to each other.
  private blockAttrs(block: BlockInfo): Array<[string, any]> {
    const header = block.getSummary()!.getHeader()!;
    const validatorId = encodeBase16(header.getValidatorPublicKey_asU8());
    return [
      ['Block hash', this.blockHashBase16],
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
        'Deploy errors',
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
  }
}

export const Block = withRouter(_Block);

const BlockLink = (props: { blockHash: ByteArray }) => {
  let id = encodeBase16(props.blockHash);
  return <Link to={Pages.block(id)}>{id}</Link>;
};

export default Block;
