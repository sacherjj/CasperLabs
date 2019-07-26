import React from 'react';
import { observer } from 'mobx-react';
import CasperContainer, { DagStep } from '../containers/CasperContainer';
import { RefreshableComponent, ListInline, IconButton } from './Utils';
import DataTable from './DataTable';
import { BlockInfo } from '../grpc/io/casperlabs/casper/consensus/info_pb';
import { encodeBase16 } from '../lib/Conversions';
import { Link } from 'react-router-dom';
import Pages from './Pages';
import TimeAgo from 'javascript-time-ago';
import en from 'javascript-time-ago/locale/en';

interface Props {
  casper: CasperContainer;
}

/** Show the list of blocks. */
@observer
export default class Blocks extends RefreshableComponent<Props, {}> {
  async refresh() {
    this.props.casper.refreshBlockDag();
  }

  render() {
    return (
      <DataTable
        title={
          this.props.casper.maxRank === 0
            ? 'Latest Blocks'
            : `Blocks from rank ${this.props.casper.minRank} to ${this.props.casper.maxRank}`
        }
        refresh={() => this.refresh()}
        headers={['Block hash', 'Rank', 'Timestamp', 'Validator']}
        rows={this.props.casper.blocks}
        renderRow={(block: BlockInfo) => {
          const header = block.getSummary()!.getHeader()!;
          const id = encodeBase16(block.getSummary()!.getBlockHash_asU8());
          const validatorId = encodeBase16(header.getValidatorPublicKey_asU8());
          return (
            <tr key={id}>
              <td>
                <Link to={Pages.block(id)}>{id}</Link>
              </td>
              <td>{header.getRank()}</td>
              <td>
                <Timestamp timestamp={header.getTimestamp()} />
              </td>
              <td>{shortHash(validatorId)}</td>
            </tr>
          );
        }}
        footerMessage={<DagStepButtons step={this.props.casper.dagStep} />}
      />
    );
  }
}

TimeAgo.addLocale(en);
const timeAgo = new TimeAgo();

const Timestamp = (props: { timestamp: number }) => {
  // Genesis has 0 timestamp which would print 50 years ago.
  const d = new Date(props.timestamp);
  return props.timestamp ? (
    <span title={d.toISOString()}>{timeAgo.format(d)}</span>
  ) : null;
};

export const DagStepButtons = (props: { step: DagStep }) => {
  return (
    <ListInline>
      <IconButton
        title="First"
        onClick={() => props.step.first()}
        icon="fast-backward"
      />
      <IconButton
        title="Previous"
        onClick={() => props.step.prev()}
        icon="step-backward"
      />
      <IconButton
        title="Next"
        onClick={() => props.step.next()}
        icon="step-forward"
      />
      <IconButton
        title="Last"
        onClick={() => props.step.last()}
        icon="fast-forward"
      />
    </ListInline>
  );
};

export const shortHash = (hash: string) =>
  hash.length > 10 ? hash.substr(0, 10) + '...' : hash;
