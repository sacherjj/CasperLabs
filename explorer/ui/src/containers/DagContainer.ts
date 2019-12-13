import { action, autorun, observable, runInAction } from 'mobx';

import ErrorContainer from './ErrorContainer';
import { CasperService } from 'casperlabs-sdk';
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

enum SubscribeState {
  ON,
  CLOSE
}

export class DagContainer {
  @observable blocks: BlockInfo[] | null = null;
  @observable selectedBlock: BlockInfo | undefined = undefined;
  @observable depth = 10;
  @observable maxRank = 0;
  @observable validatorsListToggleStore: ToggleStore = new ToggleStore(false);
  @observable lastFinalizedBlock: BlockInfo | undefined = undefined;
  @observable eventsSubscriber: Subscription | null = null;
  @observable subscribeToggleStore: ToggleStore = new ToggleStore(true);

  constructor(
    private errors: ErrorContainer,
    private casperService: CasperService
  ) {
    // so that change of subscribeToggleStore can trigger `setUpSubscriber`
    autorun(() => this.setUpSubscriber(this.subscribeToggleStore.isPressed), {
      delay: 100
    });
  }

  get minRank() {
    return Math.max(0, this.maxRank - this.depth + 1);
  }

  get hasBlocks() {
    return this.blocks ? this.blocks.length > 0 : false;
  }

  get isLatestDag() {
    return this.maxRank === 0;
  }

  private get _subscriberState(): SubscribeState {
    if (this.eventsSubscriber && !this.eventsSubscriber.closed) {
      return SubscribeState.ON;
    } else {
      return SubscribeState.CLOSE;
    }
  }

  step = new DagStep(this);

  unsubscribe() {
    if (this._subscriberState === SubscribeState.ON) {
      this.eventsSubscriber!.unsubscribe();
    }
  }

  @action
  setUpSubscriber(subscribeToggleEnabled: boolean) {
    if (this.isLatestDag && subscribeToggleEnabled) {
      // enable subscriber
      if (this._subscriberState === SubscribeState.ON) {
        // when clicking refresh button, we can reused the web socket.
        return;
      } else {
        let subscribeTopics = {
          blockAdded: true,
          blockFinalized: false
        };
        let obs = this.casperService.subscribeEvents(subscribeTopics);

        this.eventsSubscriber = obs.subscribe({
          next: (event: Event) => {
            let block = event.getBlockAdded()?.getBlock();
            if (block) {
              let index: number | undefined = this.blocks?.findIndex(
                b =>
                  b.getSummary()?.getBlockHash_asB64() ===
                  block!.getSummary()?.getBlockHash_asB64()
              );

              if (index === -1) {
                runInAction(() => this.blocks?.splice(0, 0, block!));
              }
            }
          }
        });
      }
    } else {
      // disable subscriber
      this.unsubscribe();
    }
  }

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

    this.setUpSubscriber(this.subscribeToggleStore.isPressed);
  }
}

export default DagContainer;
