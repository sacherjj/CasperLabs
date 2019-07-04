import { observable } from 'mobx';

import ErrorContainer from './ErrorContainer';

// CasperContainer talks to the API on behalf of React
// components and exposes the state in MobX observables.
export class CasperContainer {
  @observable faucetRequests: FaucetRequest[] = [];

  constructor(private errors: ErrorContainer) {}

  requestTokens(account: UserAccount) {
    const deployHash = Buffer.alloc(0);
    this.monitorFaucetRequest(account, deployHash);
  }

  private monitorFaucetRequest(account: UserAccount, deployHash: ByteArray) {
    // TODO: This should eventually be persisted on the server, so a page refresh doesn't cause it to forget.
    this.faucetRequests.push({ timestamp: new Date(), account, deployHash });
  }
}

// Record of a request we submitted.
export interface FaucetRequest {
  timestamp: Date;
  account: UserAccount;
  deployHash: ByteArray;
}

export default CasperContainer;
