import { action, observable, reaction, runInAction } from 'mobx';

import ErrorContainer from './ErrorContainer';
import { CasperService, encodeBase16 } from 'casperlabs-sdk';
import { BlockInfo, Event } from 'casperlabs-grpc/io/casperlabs/casper/consensus/info_pb';
import { Subscription } from 'rxjs';
import { ToggleStore } from '../components/ToggleButton';

export class DagStep {
  constructor(private container: DagContainer) {
  }

  private step = (f: () => number) => () => {
    this.maxRank = f();
    this.container.refreshBlockDagAndSetupSubscriber();
    this.container.selectedBlock = undefined;
  };

  get maxRank() {
    return this.container.maxRank;
  }

  get depth() {
    return this.container.depth;
  }

  set maxRank(rank: number) {
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
  UN_INIT,
  ON,
  OFF
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
    reaction(() => this.subscribeToggleStore.isPressed, (isPressed, reaction) => {
      this.setUpSubscriber(isPressed);
    }, {
      fireImmediately: false,
      delay: 100
    });
  }

  @action
  updateMaxRankAndDepth(rank: number, depth: number){
    this.maxRank = rank;
    this.depth = depth;
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

  private get subscriberState(): SubscribeState {
    if (!this.eventsSubscriber) {
      return SubscribeState.UN_INIT;
    } else if (!this.eventsSubscriber.closed) {
      return SubscribeState.ON;
    } else {
      return SubscribeState.OFF;
    }
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

  unsubscribe() {
    if (this.subscriberState === SubscribeState.ON) {
      this.eventsSubscriber!.unsubscribe();
    }
  }

  @action
  setUpSubscriber(subscribeToggleEnabled: boolean) {
    if (this.isLatestDag && subscribeToggleEnabled) {
      // enable subscriber
      if (this.subscriberState === SubscribeState.ON) {
        // when clicking refresh button, we can reused the web socket.
        return;
      } else {
        if (this.subscriberState === SubscribeState.OFF) {
          // Refresh when switching from OFF to ON
          this.refreshBlockDag();
        }

        let subscribeTopics = {
          blockAdded: true,
          blockFinalized: true
        };
        let obs = this.casperService.subscribeEvents(subscribeTopics);

        this.eventsSubscriber = obs.subscribe({
          next: (event: Event) => {
            if (event.hasBlockAdded()) {
              let block = event.getBlockAdded()?.getBlock();
              if (block) {
                let index: number | undefined = this.blocks?.findIndex(
                  b =>
                    b.getSummary()?.getBlockHash_asB64() ===
                    block!.getSummary()?.getBlockHash_asB64()
                );

                if (index === -1) {
                  // blocks with rank < N+1-depth will be culled
                  let culledThreshold = block!.getSummary()!.getHeader()!.getRank() + 1 - this.depth;
                  let remainingBlocks: BlockInfo[] = [];
                  if (this.blocks !== null) {
                    remainingBlocks = this.blocks.filter(b => {
                      let rank = b.getSummary()?.getHeader()?.getRank();
                      if (rank !== undefined) {
                        return rank >= culledThreshold;
                      }
                      return false;
                    });
                  }
                  remainingBlocks.splice(0, 0, block!);
                  runInAction(() => {
                    this.blocks = remainingBlocks;
                  });
                }
              }
            } else if (event.hasNewFinalizedBlock()) {
              this.errors.capture(
                this.casperService.getLastFinalizedBlockInfo().then(block => {
                  this.lastFinalizedBlock = block;
                })
              );
            }
          }
        });

      }
    } else {
      // disable subscriber
      this.unsubscribe();
    }
  }

  async refreshBlockDagAndSetupSubscriber() {
    await this.refreshBlockDag();
    this.setUpSubscriber(this.subscribeToggleStore.isPressed);
  }

  private async refreshBlockDag() {
    await this.errors.capture(
      this.casperService
        .getBlockInfos(this.depth, this.maxRank)
        .then(blocks => {
          this.blocks = blocks;
        })
    );

    await this.errors.capture(
      this.casperService.getLastFinalizedBlockInfo().then(block => {
        this.lastFinalizedBlock = block;
      })
    );
  }
}

export default DagContainer;
