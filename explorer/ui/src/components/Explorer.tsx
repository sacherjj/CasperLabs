import React from 'react';
import { observer } from 'mobx-react';
import CasperContainer from '../containers/CasperContainer';
import { RefreshableComponent, LinkButton, ListInline } from './Utils';
import { BlockDAG } from './BlockDAG';
import DataTable from './DataTable';
import { encodeBase16 } from '../lib/Conversions';
import { BlockInfo } from '../grpc/io/casperlabs/casper/consensus/info_pb';
import $ from 'jquery';
import { shortHash, DagStepButtons } from './Blocks';
import { Link } from 'react-router-dom';
import Pages from './Pages';

interface Props {
  casper: CasperContainer;
}

/** Show the tips of the DAG. */
@observer
export default class Explorer extends RefreshableComponent<Props, {}> {
  async refresh() {
    this.props.casper.refreshBlockDag();
  }

  render() {
    return (
      <div>
        <div className="row">
          <div
            className={`col-sm-12 col-lg-${
              this.props.casper.selectedBlock ? 8 : 12
            }`}
          >
            <BlockDAG
              title={
                this.props.casper.maxRank === 0
                  ? 'Latest Block DAG'
                  : `Block DAG from rank ${this.props.casper.minRank} to ${this.props.casper.maxRank}`
              }
              blocks={this.props.casper.blocks}
              refresh={() => this.refresh()}
              footerMessage={
                <ListInline>
                  <DagStepButtons step={this.props.casper.dagStep} />
                  {this.props.casper.hasBlocks && (
                    <span>Select a block to see its details.</span>
                  )}
                </ListInline>
              }
              onSelected={block => {
                let current = this.props.casper.selectedBlock;
                if (
                  current &&
                  current.getSummary()!.getBlockHash_asB64() ===
                    block.getSummary()!.getBlockHash_asB64()
                ) {
                  this.props.casper.selectedBlock = undefined;
                } else {
                  this.props.casper.selectedBlock = block;
                }
              }}
              selected={this.props.casper.selectedBlock}
              depth={this.props.casper.dagDepth}
              onDepthChange={d => {
                this.props.casper.dagDepth = d;
                this.refresh();
              }}
              width="100%"
              height="600"
            />
          </div>
          {this.props.casper.selectedBlock && (
            <div className="col-sm-12 col-lg-4">
              <BlockDetails
                block={this.props.casper.selectedBlock}
                blocks={this.props.casper.blocks!}
                onSelect={blockHash => {
                  this.props.casper.selectedBlock = this.props.casper.blocks!.find(
                    x =>
                      encodeBase16(x.getSummary()!.getBlockHash_asU8()) ===
                      blockHash
                  );
                }}
              />
            </div>
          )}
        </div>
      </div>
    );
  }
}

class BlockDetails extends React.Component<{
  block: BlockInfo;
  blocks: BlockInfo[];
  onSelect: (blockHash: string) => void;
}> {
  ref: HTMLElement | null = null;

  render() {
    let { block } = this.props;
    let summary = block.getSummary()!;
    let header = summary.getHeader()!;
    let id = encodeBase16(summary.getBlockHash_asU8());
    let idB64 = summary.getBlockHash_asB64();
    let validatorId = encodeBase16(header.getValidatorPublicKey_asU8());
    // Display 2 sets of fields next to each other.
    let attrs: Array<Array<[string, any]>> = [
      [
        ['Block hash', <Link to={Pages.block(id)}>{shortHash(id)}</Link>],
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
        ['Deploy count', header.getDeployCount()]
      ],
      [
        ['Validator', shortHash(validatorId)],
        ['Validator block number', header.getValidatorBlockSeqNum()]
      ],
      [
        [
          'Validator stake',
          (() => {
            let validatorBond = header
              .getState()!
              .getBondsList()
              .find(
                x =>
                  encodeBase16(x.getValidatorPublicKey_asU8()) === validatorId
              );
            // Genesis doesn't have a validator.
            return (validatorBond && validatorBond.getStake()) || null;
          })()
        ],
        ['Fault tolerance', block.getStatus()!.getFaultTolerance()]
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

  componentDidUpdate() {
    // It gets annothing when it's already visible and keeps scrolling into view.
    // this.scrollToBlockDetails();
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
