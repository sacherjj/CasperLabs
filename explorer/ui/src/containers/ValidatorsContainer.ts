import ErrorContainer from './ErrorContainer';
import { CasperService } from 'casperlabs-sdk';
import { action, computed, observable } from 'mobx';
import { BlockInfo, Event } from 'casperlabs-grpc/io/casperlabs/casper/consensus/info_pb';
import { Block } from 'casperlabs-grpc/io/casperlabs/casper/consensus/consensus_pb';
import { ToggleableSubscriber } from './ToggleableSubscriber';

// Last N ranks to construct latest messages of validators
const N = 10;

type ValidatorIdBase64 = string;

export interface ValidatorInfo {
  id: ValidatorIdBase64;
  latestBlockHash: ByteArray;
  rank: number;
  timestamp: number;
}

export class ValidatorsContainer {
  @observable latestFinalizedBlock: BlockInfo | null = null;
  // collect ValidatorInfo for each bonded validator
  @observable validatorInfoMaps: Map<ValidatorIdBase64, ValidatorInfo> = new Map();
  toggleableSubscriber: ToggleableSubscriber;

  constructor(
    private errors: ErrorContainer,
    private casperService: CasperService
  ) {
    this.toggleableSubscriber = new ToggleableSubscriber(
      {
        blockAdded: true,
        blockFinalized: true
      },
      this.casperService,
      (e) => {
        this.subscriberHandler(e);
      },
      () => true,
      () => this.refresh()
    );
  }

  @action.bound
  async refresh() {
    this.latestFinalizedBlock = await this.casperService.getLastFinalizedBlockInfo();
    this.getValidatorInfos();
  }

  @computed
  get validatorInfos(){
    return Array.from(this.validatorInfoMaps.values());
  }

  /*
   * return validators bonded in the LFB
   */
  @computed
  get bondedValidators() {
    if (!this.latestFinalizedBlock) {
      return new Set<ValidatorIdBase64>();
    } else {
      return new Set(this.latestFinalizedBlock.getSummary()!.getHeader()!.getState()!.getBondsList()!.map(b => b.getValidatorPublicKey_asB64()));
    }
  }

  // insert or update accMap when giving a list of blockInfo
  @action.bound
  private async upsert(blockInfos: BlockInfo[], candidates: Set<ValidatorIdBase64>) {
    blockInfos.forEach(b => {
      const header = b.getSummary()!.getHeader()!;
      let validatorId = header.getValidatorPublicKey_asB64();
      if (!candidates.has(validatorId)) {
        return;
      }
      let item = this.validatorInfoMaps.get(validatorId);
      if (!item || item.rank < header.getJRank()) {
        this.validatorInfoMaps.set(validatorId, {
          id: validatorId,
          latestBlockHash: b.getSummary()!.getBlockHash_asU8()!,
          rank: header.getJRank(),
          timestamp: header.getTimestamp()
        });
      }
    });
  }

  @action.bound
  private async getValidatorInfos() {
    let bondedValidators = this.bondedValidators;
    let latestRankNMsgs = await this.casperService.getBlockInfos(N, 0);

    this.upsert(latestRankNMsgs, bondedValidators);

    // for every validator that still doesn't have any info, use the justifications in the LFB, get the block info for those (there will be multiple, in current setting 3, one for each era up to the key block), use the latest by rank
    let justifications: Array<Block.Justification> = this.latestFinalizedBlock?.getSummary()?.getHeader()?.getJustificationsList() ?? new Array<Block.Justification>();
    let promises = justifications.filter(j => {
      // only request BlockInfo for those validators who has bonded but still don't have any info
      let vId = j.getValidatorPublicKey_asB64();
      return bondedValidators.has(vId) && !this.validatorInfoMaps.has(vId);
    }).map(j => {
      return this.casperService.getBlockInfo(j.getLatestBlockHash());
    });

    let blockInfos: BlockInfo[] = await Promise.all(promises);

    this.upsert(blockInfos, bondedValidators);
  };

  private subscriberHandler(e: Event) {
    if (e.hasBlockAdded()) {
      let block = e.getBlockAdded()?.getBlock();
      let candidates = this.bondedValidators;
      if (block) {
        this.upsert([block], candidates);
      }
    } else if (e.hasNewFinalizedBlock()) {
      this.errors.capture(
        this.casperService.getBlockInfo(e.getNewFinalizedBlock()!.getBlockHash()).then(_ => {
          // when receive a new finalized block, we need to recompute
          this.refresh();
        })
      );
    }
  }
}

export default ValidatorsContainer;
