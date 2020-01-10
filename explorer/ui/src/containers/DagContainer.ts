import { observable } from 'mobx';

import ErrorContainer from './ErrorContainer';
import { CasperService, encodeBase16 } from 'casperlabs-sdk';
import {
  BlockInfo,
  Event
} from 'casperlabs-grpc/io/casperlabs/casper/consensus/info_pb';
import { Subscription } from 'rxjs';
import { ToggleStore } from '../components/ToggleButton';

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
  @observable validatorsListToggleStore: ToggleStore = new ToggleStore(false);
  @observable lastFinalizedBlock: BlockInfo | undefined = undefined;
  @observable eventsSubscriber: Subscription | null = null;

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

  async selectByBlockHashBase16(blockHashBase16: string) {
    let selectedBlock = this.blocks!.find(
      x =>
        encodeBase16(x.getSummary()!.getBlockHash_asU8()) ===
        blockHashBase16
    );
    if (selectedBlock) {
      this.selectedBlock = selectedBlock;
    } else {
      await this.errors.capture(
        this.casperService.getBlockInfo(blockHashBase16, 0).then(block => {
          this.selectedBlock = block;
          this.blocks!.push(block);
        })
      );
    }
  }

  step = new DagStep(this);

  async refreshBlockDag() {
    await this.errors.capture(
      this.casperService
        .getBlockInfos(this.depth, this.maxRank)
        .then(blocks => {
          this.blocks = blocks;
        })
    );

    await this.errors.capture(
      this.casperService.getLatestBlockInfo().then(block => {
        this.lastFinalizedBlock = block;
      })
    );

    if (this.eventsSubscriber && !this.eventsSubscriber.closed) {
      return;
    } else {
      let subscribeTopics = {
        blockAdded: true,
        blockFinalized: false
      };
      let obs = this.casperService.subscribeEvents(subscribeTopics);

      this.eventsSubscriber = obs.subscribe({
        next(event: Event) {
          console.log("received event");
        }
      });
    }
  }
}

export default DagContainer;
