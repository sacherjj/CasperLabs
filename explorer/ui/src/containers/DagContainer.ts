import { observable } from 'mobx';

import ErrorContainer from './ErrorContainer';
import CasperService from '../services/CasperService';
import { BlockInfo } from '../grpc/io/casperlabs/casper/consensus/info_pb';

export class DagStep {
  constructor(private container: DagContainer) {}

  private step = (f: () => number) => () => {
    this.maxRank = f();
    this.container.refreshBlockDag();
    this.container.selectedBlock = undefined;
  };

  private get maxRank() {
    return this.container.maxRank;
  }

  private get depth() {
    return this.container.depth;
  }

  private set maxRank(rank: number) {
    this.container.maxRank = rank;
  }

  private get currentMaxRank() {
    let blockRank =
      this.container.hasBlocks &&
      this.container
        .blocks![0].getSummary()!
        .getHeader()!
        .getRank();
    return this.maxRank === 0 && blockRank ? blockRank : this.maxRank;
  }

  first = this.step(() => this.depth - 1);

  prev = this.step(() =>
    this.maxRank === 0 && this.currentMaxRank <= this.depth
      ? 0
      : this.currentMaxRank > this.depth
      ? this.currentMaxRank - this.depth
      : this.currentMaxRank
  );

  next = this.step(() => this.currentMaxRank + this.depth);

  last = this.step(() => 0);
}

export class DagContainer {
  @observable blocks: BlockInfo[] | null = null;
  @observable selectedBlock: BlockInfo | undefined = undefined;
  @observable depth = 10;
  @observable maxRank = 0;

  constructor(
    private errors: ErrorContainer,
    private casperService: CasperService
  ) {}

  get minRank() {
    return Math.max(0, this.maxRank - this.depth + 1);
  }

  get hasBlocks() {
    return this.blocks ? this.blocks.length > 0 : false;
  }

  step = new DagStep(this);

  async refreshBlockDag() {
    this.errors.capture(
      this.casperService
        .getBlockInfos(this.depth, this.maxRank)
        .then(blocks => {
          this.blocks = blocks;
        })
    );
  }
}

export default DagContainer;
