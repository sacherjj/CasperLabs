import { observable, action, computed } from 'mobx';

import ErrorContainer from './ErrorContainer';
import CasperService from '../services/CasperService';
import { BlockInfo } from '../grpc/io/casperlabs/casper/consensus/info_pb';
import { encodeBase16 } from '../lib/Conversions';
import { Block } from '../grpc/io/casperlabs/casper/consensus/consensus_pb';
import ObservableValueMap from '../lib/ObservableValueMap';
import BalanceService from '../services/BalanceService';

type AccountB16 = string;

export class BlockContainer {
  @observable blockHash: ByteArray | null = null;
  @observable block: BlockInfo | null = null;
  @observable neighborhood: BlockInfo[] | null = null;
  // How much of the DAG to load around the block.
  @observable depth = 10;
  @observable deploys: Block.ProcessedDeploy[] | null = null;
  @observable balances: ObservableValueMap<
    AccountB16,
    number
  > = new ObservableValueMap();

  constructor(
    private errors: ErrorContainer,
    private casperService: CasperService,
    private balanceService: BalanceService
  ) {}

  /** Call whenever the page switches to a new block. */
  @action
  init(blockHash: ByteArray) {
    this.blockHash = blockHash;
    this.block = null;
    this.neighborhood = null;
    this.deploys = null;
    this.balances.clear();
  }

  @computed get blockHashBase16() {
    return this.blockHash && encodeBase16(this.blockHash);
  }

  async loadBlock() {
    if (this.blockHash == null) return;
    await this.errors.capture(
      this.casperService.getBlockInfo(this.blockHash).then(block => {
        this.block = block;
      })
    );
  }

  async loadNeighborhood() {
    if (this.block == null) {
      this.neighborhood = null;
      return;
    }

    const maxRank =
      this.block
        .getSummary()!
        .getHeader()!
        .getRank() +
      this.depth / 2;

    // Adjust the depth so it doesn't result in a negative start value.
    let depth = Math.min(maxRank + 1, this.depth);

    await this.errors.capture(
      this.casperService.getBlockInfos(depth, maxRank).then(blocks => {
        this.neighborhood = blocks;
      })
    );
  }

  async loadDeploys() {
    if (this.blockHash == null) {
      this.deploys = null;
      return;
    }
    await this.errors.capture(
      this.casperService.getBlockDeploys(this.blockHash).then(deploys => {
        this.deploys = deploys;
      })
    );
  }

  /** Load the balances of accounts that executed deploys in this block. */
  async loadBalances() {
    if (this.deploys == null || this.blockHash == null) {
      return;
    }
    for (let deploy of this.deploys) {
      const accountKey = deploy
        .getDeploy()!
        .getHeader()!
        .getAccountPublicKey_asU8();
      const accountB16 = encodeBase16(accountKey);
      const balance = await this.balanceService.getAccountBalance(
        this.blockHash,
        accountKey
      );
      if (balance !== undefined) {
        this.balances.set(accountB16, balance);
      }
    }
  }
}

export default BlockContainer;
