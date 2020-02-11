import ErrorContainer from './ErrorContainer';
import { CasperService } from 'casperlabs-sdk';
import { StateQuery } from 'casperlabs-grpc/io/casperlabs/node/api/casper_pb';
import { observable } from 'mobx';


export class VestingContainer {
  @observable vestingDetails: VestingDetail | null = null;

  constructor(
    private errors: ErrorContainer,
    private casperService: CasperService
  ) {
  }

  /** Call whenever the page switches to a new vesting contract. */
  async init(hash: string, showLoading: boolean = false) {
    // show loading
    if (showLoading) {
      this.vestingDetails = null;
    }
    this.vestingDetails = await this.getVestingDetails(hash);
  }


  private async getVestingDetails(keyBase16: string) {
    let res = new VestingDetail();
    let paths = Object.keys(res);
    let stateQueries = paths.map(p => {
      let s = new StateQuery();
      s.setKeyBase16(keyBase16);
      s.setKeyVariant(StateQuery.KeyVariant.HASH);
      s.setPathSegmentsList([p]);
      return s;
    });
    let lastFinalizedBlockInfo = await this.casperService.getLastFinalizedBlockInfo();
    let values = await this.casperService.batchGetBlockState(lastFinalizedBlockInfo.getSummary()!.getBlockHash_asU8(), stateQueries);
    for (let i = 0; i < values.length; i++) {
      (res as any)[paths[i]] = Number(values[i].getBigInt()!.getValue());
    }
    return res;
  }
}

export class VestingDetail {
  cliff_timestamp: number;
  cliff_amount: number;
  total_amount: number;
  released_amount: number;
  on_pause_duration: number;
  last_pause_timestamp: number;
  drip_duration: number;
  drip_amount: number;
  admin_release_duration: number;

  // Todo fetch from global state storage once the parsing bug is fixed.
  get admin_account(): string {
    return "ad1ce8c63f6439c12a6c57f8d797e2a1ea7af76ccdcc08b83baa5f84ffc180f1";
  }

  // Todo fetch from global state storage once the parsing bug is fixed.
  get recipient_account(): string {
    return "400ceb75b8ad14a395edd03a285cc2de745cc61bef22e5a8e214a9783505409c";
  }

  // Todo fetch from global state storage once the parsing bug is fixed.
  get is_paused(): boolean {
    return false;
  }

  // check whether the contract is releasable by admin account
  get is_releasable(): boolean{
    if (!this.is_paused) {
      return false;
    }
    let since_last_pause = Date.now() - this.last_pause_timestamp;
    if( since_last_pause < this.admin_release_duration ){
      return false;
    }
    if (this.total_amount === this.released_amount) {
      return false;
    }
    return true;
  }

  get total_paused_duration(): number {
    let duration = this.on_pause_duration;
    let current_timestamp = Date.now();

    if (this.is_paused) {
      duration += current_timestamp - this.last_pause_timestamp;
    }

    return duration;
  }

  get available_amount() {
    let current_timestamp = Date.now();
    let total_paused_duration = this.total_paused_duration;
    let cliff_timestamp_adjusted = this.cliff_timestamp + total_paused_duration;
    if (current_timestamp < cliff_timestamp_adjusted) {
      return 0;
    } else {
      let time_diff = current_timestamp - cliff_timestamp_adjusted;
      let available_drips = 0;
      if (this.drip_duration !== 0) {
        available_drips = time_diff / this.drip_duration;
      }
      let counter = this.cliff_amount;
      counter += this.drip_amount * available_drips;
      counter = Math.min(counter, this.total_amount);
      return counter - this.released_amount;
    }
  }
}
