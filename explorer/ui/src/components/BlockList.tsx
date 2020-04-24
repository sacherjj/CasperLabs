import React, { Component } from 'react';
import { observer } from 'mobx-react';
import DagContainer, { DagStep } from '../containers/DagContainer';
import {
  IconButton,
  ListInline,
  RefreshableComponent,
  shortHash
} from './Utils';
import DataTable from './DataTable';
import { BlockInfo } from 'casperlabs-grpc/io/casperlabs/casper/consensus/info_pb';
import { Link, RouteComponentProps, withRouter } from 'react-router-dom';
import Pages from './Pages';
import { encodeBase16 } from 'casperlabs-sdk';
import Timestamp from './TimeStamp';
import { BlockType } from './BlockDetails';
import * as H from 'history';

export interface Props extends RouteComponentProps<{}> {
  dag: DagContainer;
  maxRank: string | null;
  depth: string | null;
}

@observer
class _BlockList extends React.Component<Props, {}> {
  constructor(props: Props) {
    super(props);
    let maxRank = parseInt(props.maxRank || '') || 0;
    let depth = parseInt(props.depth || '') || 10;
    this.props.dag.updateMaxRankAndDepth(maxRank, depth);
    this.props.dag.refreshBlockDagAndSetupSubscriber();
  }

  async refresh() {
    await this.props.dag.refreshBlockDagAndSetupSubscriber();
  }

  componentWillUnmount() {
    // release websocket if necessary
    this.props.dag.toggleableSubscriber.unsubscribeAndFree();
  }

  render() {
    const { dag } = this.props;
    return (
      <DataTable
        title={
          dag.maxRank === 0
            ? 'Latest Blocks'
            : `Blocks from rank ${dag.minRank} to ${dag.maxRank}`
        }
        refresh={() => this.refresh()}
        subscribeToggleStore={dag.toggleableSubscriber.subscribeToggleStore}
        headers={[
          'Block Hash',
          'j-Rank',
          'm-Rank',
          'Timestamp',
          'Validator',
          'Type',
          'Key Block Hash'
        ]}
        rows={dag.blocks}
        renderRow={(block: BlockInfo) => {
          const header = block.getSummary()!.getHeader()!;
          const id = encodeBase16(block.getSummary()!.getBlockHash_asU8());
          return (
            <tr key={id}>
              <td>
                <Link to={Pages.block(id)}>{id}</Link>
              </td>
              <td>{header.getJRank()}</td>
              <td>{header.getMainRank()}</td>
              <td>
                <Timestamp timestamp={header.getTimestamp()} />
              </td>
              <td>{shortHash(header.getValidatorPublicKey_asU8())}</td>
              <td>
                <BlockType header={header} />
              </td>
              <td>
                <Link
                  to={Pages.block(encodeBase16(header.getKeyBlockHash_asU8()))}
                >
                  {shortHash(header.getKeyBlockHash_asU8())}
                </Link>
              </td>
            </tr>
          );
        }}
        footerMessage={
          <DagStepButtons
            step={dag.step}
            history={this.props.history}
            urlWithRankAndDepth={Pages.blocksWithMaxRankAndDepth}
          />
        }
      />
    );
  }
}

const BlockList = withRouter(_BlockList);
export default BlockList;

export const DagStepButtons = (props: {
  step: DagStep;
  history: H.History;
  urlWithRankAndDepth: (maxRank: number, depth: number) => string;
}) => {
  const updateUrlQuery = (stepFunc: () => void) => {
    stepFunc();
    props.history.push(
      props.urlWithRankAndDepth(props.step.maxRank, props.step.depth)
    );
  };
  return (
    <ListInline>
      <IconButton
        title="First"
        onClick={() => updateUrlQuery(props.step.first)}
        icon="fast-backward"
      />
      <IconButton
        title="Previous"
        onClick={() => updateUrlQuery(props.step.prev)}
        icon="step-backward"
      />
      <IconButton
        title="Next"
        onClick={() => updateUrlQuery(props.step.next)}
        icon="step-forward"
      />
      <IconButton
        title="Last"
        onClick={() => updateUrlQuery(props.step.last)}
        icon="fast-forward"
      />
    </ListInline>
  );
};
