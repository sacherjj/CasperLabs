import React from 'react';
import { observer } from 'mobx-react';
import {
  LinkButton,
  ListInline,
  RefreshableComponent,
  shortHash
} from './Utils';
import { BlockDAG } from './BlockDAG';
import DataTable from './DataTable';
import { BlockInfo } from 'casperlabs-grpc/io/casperlabs/casper/consensus/info_pb';
import $ from 'jquery';
import { DagStepButtons, Props } from './BlockList';
import { Link, withRouter } from 'react-router-dom';
import Pages from './Pages';
import { encodeBase16 } from 'casperlabs-sdk';
import { BondedValidatorsTable } from './BondedValidatorsTable';
import { ToggleButton } from './ToggleButton';

/** Show the tips of the DAG. */
@observer
class _Explorer extends RefreshableComponent<Props, {}> {
  constructor(props:Props) {
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
    super.componentWillUnmount();
    // release websocket if necessary
    this.props.dag.unsubscribe();
  }

  render() {
    const { dag } = this.props;
    return (
      <div>
        <div className="row">
          <div className={`col-sm-12 col-lg-${dag.selectedBlock ? 8 : 12}`}>
            <BlockDAG
              title={
                dag.isLatestDag
                  ? 'Latest Block DAG'
                  : `Block DAG from rank ${dag.minRank} to ${dag.maxRank}`
              }
              blocks={dag.blocks}
              refresh={() => this.refresh()}
              subscribeToggleStore={dag.subscribeToggleStore}
              footerMessage={
                <ListInline>
                  <DagStepButtons
                    step={dag.step}
                    history={this.props.history}
                    urlWithRankAndDepth={Pages.explorerWithMaxRankAndDepth}
                  />
                  {dag.hasBlocks && (
                    <span>Select a block to see its details.</span>
                  )}
                  {dag.selectedBlock && (
                    <ToggleButton
                      title={'Show bonded validators'}
                      toggleStore={dag.validatorsListToggleStore}
                      size="sm"
                    />
                  )}
                </ListInline>
              }
              onSelected={block => {
                let current = dag.selectedBlock;
                if (
                  current &&
                  current.getSummary()!.getBlockHash_asB64() ===
                    block.getSummary()!.getBlockHash_asB64()
                ) {
                  dag.selectedBlock = undefined;
                } else {
                  dag.selectedBlock = block;
                }
              }}
              selected={dag.selectedBlock}
              depth={dag.depth}
              onDepthChange={d => {
                dag.depth = d;
                this.refresh();
              }}
              width="100%"
              height="600"
            />
          </div>
          {dag.selectedBlock && (
            <div className="col-sm-12 col-lg-4">
              <BlockDetails
                block={dag.selectedBlock}
                blocks={dag.blocks!}
                onSelect={blockHashBase16 => {
                  dag.selectByBlockHashBase16(blockHashBase16);
                }}
              />
            </div>
          )}
          {dag.selectedBlock && dag.validatorsListToggleStore.isPressed && (
            <div className="col-sm-12">
              <BondedValidatorsTable
                block={dag.selectedBlock}
                lastFinalizedBlock={dag.lastFinalizedBlock}
              />
            </div>
          )}
        </div>
      </div>
    );
  }
}

const Explorer = withRouter(_Explorer);
export default Explorer;

class BlockDetails extends React.Component<
  {
    block: BlockInfo;
    blocks: BlockInfo[];
    onSelect: (blockHash: string) => void;
  },
  {}
> {
  ref: HTMLElement | null = null;

  render() {
    let { block } = this.props;
    let summary = block.getSummary()!;
    let header = summary.getHeader()!;
    let id = encodeBase16(summary.getBlockHash_asU8());
    let idB64 = summary.getBlockHash_asB64();
    let validatorId = encodeBase16(header.getValidatorPublicKey_asU8());
    // Grouped attributes so we could display 2 sets of fields next to each other.
    let attrs: Array<Array<[string, any]>> = [
      [
        ['Block Hash', <Link to={Pages.block(id)}>{shortHash(id)}</Link>],
        ['Rank', header.getRank()]
      ],
      [
        [
          'Parents',
          <ul>
            {header.getParentHashesList_asU8().map((x, idx) => (
              <li key={idx}>
                <BlockLink blockHash={x} onClick={this.props.onSelect} />
              </li>
            ))}
          </ul>
        ],
        [
          'Children',
          <ul>
            {this.props.blocks
              .filter(
                b =>
                  b
                    .getSummary()!
                    .getHeader()!
                    .getParentHashesList_asB64()
                    .findIndex(p => p === idB64) > -1
              )
              .map((b, idx) => (
                <li key={idx}>
                  <BlockLink
                    blockHash={b.getSummary()!.getBlockHash_asU8()}
                    onClick={this.props.onSelect}
                  />
                </li>
              ))}
          </ul>
        ]
      ],
      [
        ['Timestamp', new Date(header.getTimestamp()).toISOString()],
        ['Deploy Count', header.getDeployCount()]
      ],
      [
        ['Validator', shortHash(validatorId)],
        ['Validator Block Number', header.getValidatorBlockSeqNum()]
      ],
      [
        [
          'Validator Stake',
          (() => {
            let validatorBond = header
              .getState()!
              .getBondsList()
              .find(
                x =>
                  encodeBase16(x.getValidatorPublicKey_asU8()) === validatorId
              );
            // Genesis doesn't have a validator.
            return (
              (validatorBond &&
                validatorBond.getStake() &&
                Number(
                  validatorBond.getStake()!.getValue()
                ).toLocaleString()) ||
              null
            );
          })()
        ],
        [
          'Fault Tolerance',
          block
            .getStatus()!
            .getFaultTolerance()
            .toFixed(3)
        ]
      ]
    ];
    return (
      <div
        ref={x => {
          this.ref = x;
        }}
      >
        <DataTable
          title={`Block ${shortHash(id)}`}
          headers={[]}
          rows={attrs}
          renderRow={(group, i) =>
            // <tr key={i}>
            //   {
            //     group.flatMap((attr, j) => [
            //       <th key={'${i}/${j}/0'}>{attr[0]}</th>,
            //       <td key={'${i}/${j}/1'}>{attr[1]}</td>
            //     ])
            //   }
            // </tr>
            group.flatMap((attr, j) => [
              <tr key={`${i}/${j}`}>
                <th>{attr[0]}</th>
                <td>{attr[1]}</td>
              </tr>
            ])
          }
          footerMessage="Click the links to select the parents and children."
        />
      </div>
    );
  }

  componentDidMount() {
    // Scroll into view so people realize it's there.
    this.scrollToBlockDetails();
  }

  scrollToBlockDetails() {
    let container = $(this.ref!);
    let offset = container.offset()!;
    let height = container.height()!;
    $('html, body')
      .stop()
      .animate(
        {
          scrollTop: offset.top + height
        },
        1000
      );
  }
}

const BlockLink = (props: {
  blockHash: ByteArray;
  onClick: (blockHashBase16: string) => void;
}) => {
  let id = encodeBase16(props.blockHash);
  return <LinkButton title={shortHash(id)} onClick={() => props.onClick(id)} />;
};
