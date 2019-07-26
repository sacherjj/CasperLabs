import React from 'react';
import { observer } from 'mobx-react';
import CasperContainer from '../containers/CasperContainer';
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

  private step = (f: () => void) => () => {
    f();
    this.refresh();
    // TODO: Add the `maxRank` parameter to the URL.
    // Alternatively we could just encode the actions as a Link.
  };

  private get maxRank() {
    return this.props.casper.maxRank;
  }

  private get dagDepth() {
    return this.props.casper.dagDepth;
  }

  private set maxRank(rank: number) {
    this.props.casper.maxRank = rank;
  }

  private first = this.step(() => (this.maxRank = this.dagDepth));

  private prev = this.step(() => {
    let blockRank =
      this.props.casper.blocks &&
      this.props.casper.blocks[0]
        .getSummary()!
        .getHeader()!
        .getRank();
    let maxRank =
      this.maxRank === 0 && blockRank
        ? blockRank - (blockRank % this.dagDepth) + this.dagDepth
        : this.maxRank === 0
        ? this.dagDepth
        : this.maxRank;
    this.maxRank = Math.max(this.dagDepth, maxRank - this.dagDepth);
  });

  private next = this.step(
    () => (this.maxRank = this.maxRank === 0 ? 0 : this.maxRank + this.dagDepth)
  );

  private last = this.step(() => (this.maxRank = 0));

  render() {
    return (
      <DataTable
        title={
          this.maxRank ? `Blocks up to rank ${this.maxRank}` : `Latest blocks`
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
                <Link to={Pages.block(id)}>{shortHash(id)}</Link>
              </td>
              <td>{header.getRank()}</td>
              <td>
                <Timestamp timestamp={header.getTimestamp()} />
              </td>
              <td>{shortHash(validatorId)}</td>
            </tr>
          );
        }}
        footerMessage={
          <ListInline>
            <IconButton
              title="First"
              onClick={() => this.first()}
              icon="fast-backward"
            />
            <IconButton
              title="Previous"
              onClick={() => this.prev()}
              icon="step-backward"
            />
            <IconButton
              title="Next"
              onClick={() => this.next()}
              icon="step-forward"
            />
            <IconButton
              title="Last"
              onClick={() => this.last()}
              icon="fast-forward"
            />
          </ListInline>
        }
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

export const shortHash = (hash: string) =>
  hash.length > 10 ? hash.substr(0, 10) + '...' : hash;
