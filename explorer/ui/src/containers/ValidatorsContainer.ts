import ErrorContainer from './ErrorContainer';
import { CasperService } from 'casperlabs-sdk';
import { computed, observable } from 'mobx';
import { BlockInfo } from 'casperlabs-grpc/io/casperlabs/casper/consensus/info_pb';
import { Block } from 'casperlabs-grpc/io/casperlabs/casper/consensus/consensus_pb';

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
  @observable validatorInfos: ValidatorInfo[] | null = null;

  constructor(
    private errors: ErrorContainer,
    private casperService: CasperService
  ) {

  }

  async refresh() {
    this.latestFinalizedBlock = await this.casperService.getLastFinalizedBlockInfo();
    this.validatorInfos = await this.getValidatorInfos();
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

  public async getValidatorInfos() {
    // insert or update accMap when giving a list of blockInfo
    let upsert = (accMap: Map<ValidatorIdBase64, ValidatorInfo>, blockInfos: BlockInfo[], candidates: Set<ValidatorIdBase64>) => {
      blockInfos.forEach(b => {
        const header = b.getSummary()!.getHeader()!;
        let validatorId = header.getValidatorPublicKey_asB64();
        if (!candidates.has(validatorId)) {
          return;
        }
        let item = validatorInfoMap.get(validatorId);
        if (!item || item.rank < header.getJRank()) {
          validatorInfoMap.set(validatorId, {
            id: validatorId,
            latestBlockHash: b.getSummary()!.getBlockHash_asU8()!,
            rank: header.getJRank(),
            timestamp: header.getTimestamp()
          });
        }
      });
    };

    // collect ValidatorInfo for each bonded validator
    let validatorInfoMap = new Map<ValidatorIdBase64, ValidatorInfo>();
    let bondedValidators = this.bondedValidators;
    let latestRankNMsgs = await this.casperService.getBlockInfos(N, 0);

    upsert(validatorInfoMap, latestRankNMsgs, bondedValidators);

    // for every validator that still doesn't have any info, use the justifications in the LFB, get the block info for those (there will be multiple, in current setting 3, one for each era up to the key block), use the latest by rank
    let justifications: Array<Block.Justification> = this.latestFinalizedBlock?.getSummary()?.getHeader()?.getJustificationsList() ?? new Array<Block.Justification>();
    let promises = justifications.filter(j => {
      // only request BlockInfo for those validators who has bonded but still don't have any info
      let vId = j.getValidatorPublicKey_asB64();
      return bondedValidators.has(vId) && !validatorInfoMap.has(vId);
    }).map(j => {
      return this.casperService.getBlockInfo(j.getLatestBlockHash());
    });

    let blockInfos: BlockInfo[] = await Promise.all(promises);

    upsert(validatorInfoMap, blockInfos, bondedValidators);

    return Array.from(validatorInfoMap.values());
  };
}

export default ValidatorsContainer;
