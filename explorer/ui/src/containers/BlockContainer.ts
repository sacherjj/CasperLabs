import { observable } from 'mobx';

import ErrorContainer from './ErrorContainer';
import CasperService from '../services/CasperService';
import { BlockInfo } from '../grpc/io/casperlabs/casper/consensus/info_pb';

export class BlockContainerFactory {
  @observable block: BlockInfo | null = null;

  constructor(
    private errors: ErrorContainer,
    private casperService: CasperService
  ) {}

  init(blockHash: ByteArray) {
    return new BlockContainer(this.errors, this.casperService, blockHash);
  }
}

export class BlockContainer {
  @observable block: BlockInfo | null = null;

  constructor(
    private errors: ErrorContainer,
    private casperService: CasperService,
    public blockHash: ByteArray
  ) {}

  async loadBlock() {
    this.errors.capture(
      this.casperService.getBlockInfo(this.blockHash).then(block => {
        this.block = block;
      })
    );
  }
}
