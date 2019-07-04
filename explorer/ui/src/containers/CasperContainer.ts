import { computed } from 'mobx';

import ErrorContainer from './ErrorContainer';
import Cell from '../lib/Cell';

// CasperContainer talks to the API on behalf of React
// components and exposes the state in MobX observables.
export class CasperContainer {
  _faucetRequests = new Cell<FaucetRequest[]>('faucet-requests', []);

  constructor(private errors: ErrorContainer) {}

  requestTokens(account: UserAccount) {
    const deployHash = Buffer.alloc(0);
    this.monitorFaucetRequest(account, deployHash);
  }

  @computed get faucetRequests() {
    return this._faucetRequests.get;
  }

  private monitorFaucetRequest(account: UserAccount, deployHash: ByteArray) {
    const request = { timestamp: new Date(), account, deployHash };
    const requests = this._faucetRequests.get.concat(request);
    this._faucetRequests.set(requests);
  }
}

// Record of a request we submitted.
export interface FaucetRequest {
  timestamp: Date;
  account: UserAccount;
  deployHash: ByteArray;
}

export default CasperContainer;
