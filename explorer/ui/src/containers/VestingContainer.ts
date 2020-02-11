import ErrorContainer from './ErrorContainer';
import { CasperService} from 'casperlabs-sdk';
import { StateQuery } from 'casperlabs-grpc/io/casperlabs/node/api/casper_pb';
import { observable } from 'mobx';


export class VestingContainer {
  @observable vestingDetails: VestingDetail | null = null;

  constructor(
    private errors: ErrorContainer,
    private casperService: CasperService,
  ) {
  }

  /** Call whenever the page switches to a new vesting contract. */
  async init(hash: string, showLoading: boolean = false) {
    // show loading
    if(showLoading){
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

export class VestingDetail{
  cliff_timestamp : number;
  cliff_amount: number;
  total_amount: number;
  released_amount: number;
  on_pause_duration:number;
  last_pause_timestamp:number;
  drip_duration: number;
  drip_amount:number;
  admin_release_duration: number;
}
