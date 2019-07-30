import { observable, action, computed } from 'mobx';

import ErrorContainer from './ErrorContainer';
import CasperService from '../services/CasperService';
import { BlockInfo } from '../grpc/io/casperlabs/casper/consensus/info_pb';
import { encodeBase16 } from '../lib/Conversions';
import { Block } from '../grpc/io/casperlabs/casper/consensus/consensus_pb';
import { Key } from '../grpc/io/casperlabs/casper/consensus/state_pb';
import ObservableValueMap from '../lib/ObservableValueMap';

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

  // We can cache the balance URef so 2nd time the balances only need 1 query, not 4.
  private balanceUrefs = new Map<AccountB16, Key.URef>();

  constructor(
    private errors: ErrorContainer,
    private casperService: CasperService
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
    return this.errors.capture(
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

    return this.errors.capture(
      this.casperService.getBlockInfos(this.depth, maxRank).then(blocks => {
        this.neighborhood = blocks;
      })
    );
  }

  async loadDeploys() {
    if (this.blockHash == null) {
      this.deploys = null;
      return;
    }
    return this.errors.capture(
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
      let accountKey = deploy
        .getDeploy()!
        .getHeader()!
        .getAccountPublicKey_asU8();
      let accountB16 = encodeBase16(accountKey);
      let balanceUref = this.balanceUrefs.get(accountB16);
      if (!balanceUref) {
        balanceUref = await this.casperService.getAccountBalanceUref(
          this.blockHash,
          deploy
            .getDeploy()!
            .getHeader()!
            .getAccountPublicKey_asU8()
        );
        if (balanceUref) {
          this.balanceUrefs.set(accountB16, balanceUref);
        }
      }
      if (balanceUref) {
        let balance = await this.casperService.getAccountBalance(
          this.blockHash,
          balanceUref
        );
        this.balances.set(accountB16, balance);
      }
    }
  }
}

export default BlockContainer;
