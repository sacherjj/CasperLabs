import ErrorContainer from './ErrorContainer';
import { CasperService } from 'casperlabs-sdk';
import { StateQuery } from 'casperlabs-grpc/io/casperlabs/node/api/casper_pb';
import { observable } from 'mobx';
import moment from 'moment';
import { snakeCase } from "change-case";


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
      s.setPathSegmentsList([snakeCase(p)]);
      return s;
    });
    let lastFinalizedBlockInfo = await this.casperService.getLastFinalizedBlockInfo();
    let values = await this.casperService.batchGetBlockState(lastFinalizedBlockInfo.getSummary()!.getBlockHash_asU8(), stateQueries);
    for (let i = 0; i < values.length; i++) {
      let value = Number(values[i].getBigInt()!.getValue());
      // The vesting contract use seconds based timestamp/duration
      // multiple 1000 to be milliseconds based timestamp/duration
      if(paths[i].endsWith("Timestamp") || paths[i].endsWith("Duration")){
        value = value * 1000;
      }
      (res as any)[paths[i]] = value;
    }
    return res;
  }
}

// The unit of all time-related fields is millisecond
export class VestingDetail {
  cliffTimestamp: number;
  cliffAmount: number;
  totalAmount: number;
  releasedAmount: number;
  onPauseDuration: number;
  lastPauseTimestamp: number;
  dripDuration: number;
  dripAmount: number;
  adminReleaseDuration: number;

  // Todo(ECO-321): fetching from global state storage once the parsing bug is fixed.
  get adminAccount(): string {
    return 'ad1ce8c63f6439c12a6c57f8d797e2a1ea7af76ccdcc08b83baa5f84ffc180f1';
  }

  // Todo(ECO-321): fetching from global state storage once the parsing bug is fixed.
  get recipientAccount(): string {
    return '400ceb75b8ad14a395edd03a285cc2de745cc61bef22e5a8e214a9783505409c';
  }

  // Todo(ECO-321): fetching from global state storage once the parsing bug is fixed.
  get isPaused(): boolean {
    return false;
  }

  // Check whether the contract is releasable by admin account
  get isReleasable(): boolean {
    if (!this.isPaused) {
      return false;
    }
    let since_last_pause = Date.now() - this.lastPauseTimestamp;
    if (since_last_pause < this.adminReleaseDuration) {
      return false;
    }
    if (this.totalAmount === this.releasedAmount) {
      return false;
    }
    return true;
  }

  get totalPausedDuration(): number {
    let duration = this.onPauseDuration;
    let current_timestamp = Date.now();

    if (this.isPaused) {
      duration += current_timestamp - this.lastPauseTimestamp;
    }

    return duration;
  }

  getSchedulePoints(): { x: string; y: number }[] {
    let formatDate = (date: number) => {
      return moment(date).format('MM/DD/YYYY HH:mm');
    };

    const points = [];
    let time = this.cliffTimestamp + this.onPauseDuration;
    let p;
    do {
      p = this.getDataPointAt(time);
      points.push(p);
      time += this.dripDuration;
    } while (p.y < this.totalAmount);
    points.push(this.getDataPointAt(Date.now()));
    let ret = points
      .sort((a, b) => a.x - b.x)
      .map(x => {
        return {
          x: formatDate(x.x),
          y: x.y
        };
      });

    return ret;
  }

  private getDataPointAt(date: number) {
    return {
      x: date,
      y: this.getAmountAt(date)
    };
  }

  private getAmountAt(date: number): number {
    let total_paused_duration = this.totalPausedDuration;
    let cliff_timestamp_adjusted = this.cliffTimestamp + total_paused_duration;
    if (date < cliff_timestamp_adjusted) {
      return 0;
    } else {
      let time_diff = date - cliff_timestamp_adjusted;
      let available_drips = 0;
      if (this.dripDuration !== 0) {
        available_drips = time_diff / this.dripDuration;
      }
      let counter = this.cliffAmount;
      counter += this.dripAmount * available_drips;
      counter = Math.min(counter, this.totalAmount);
      return counter;
    }
  }

  get available_amount() {
    return this.getAmountAt(Date.now()) - this.releasedAmount;
  }
}
