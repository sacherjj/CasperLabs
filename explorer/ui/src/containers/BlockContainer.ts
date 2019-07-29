import { observable, action, computed } from 'mobx';

import ErrorContainer from './ErrorContainer';
import CasperService from '../services/CasperService';
import { BlockInfo } from '../grpc/io/casperlabs/casper/consensus/info_pb';
import { encodeBase16 } from '../lib/Conversions';

export class BlockContainerFactory {
  constructor(
    private errors: ErrorContainer,
    private casperService: CasperService
  ) {}

  make() {
    return new BlockContainer(this.errors, this.casperService);
  }
}

export class BlockContainer {
  @observable blockHash: ByteArray | null = null;
  @observable block: BlockInfo | null = null;
  @observable neighborhood: BlockInfo[] | null = null;
  // How much of the DAG to load around the block.
  depth = 10;

  constructor(
    private errors: ErrorContainer,
    private casperService: CasperService
  ) {}

  @action
  init(blockHash: ByteArray) {
    this.blockHash = blockHash;
    this.block = null;
    this.neighborhood = null;
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
        .getRank() + 5;
    return this.errors.capture(
      this.casperService.getBlockInfos(this.depth, maxRank).then(blocks => {
        this.neighborhood = blocks;
      })
    );
  }
}
