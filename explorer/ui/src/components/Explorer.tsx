import React from 'react';
import { observer } from 'mobx-react';
import CasperContainer from '../containers/CasperContainer';
import { RefreshableComponent } from './Utils';
import { BlockDAG } from './BlockDAG';

interface Props {
  casper: CasperContainer;
}

const DefaultDepth = 10;

/** Show the tips of the DAG. */
@observer
export default class Explorer extends RefreshableComponent<Props, {}> {
  async refresh() {
    this.props.casper.refreshBlockDag(DefaultDepth);
  }

  render() {
    return (
      <div>
        <BlockDAG
          title="Block DAG tips"
          blocks={this.props.casper.blocks}
          refresh={() => this.refresh()}
          footerMessage="Select a block to see its details."
          width="100%"
          height="600"
        />
      </div>
    );
  }
}
