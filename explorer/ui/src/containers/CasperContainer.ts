import { computed } from 'mobx';

import ErrorContainer from './ErrorContainer';
import StorageCell from '../lib/StorageCell';
import FaucetService from '../services/FaucetService';

// CasperContainer talks to the API on behalf of React
// components and exposes the state in MobX observables.
export class CasperContainer {
  _faucetRequests = new StorageCell<FaucetRequest[]>('faucet-requests', []);

  constructor(
    private errors: ErrorContainer,
    private faucetService: FaucetService
  ) {}

  async requestTokens(account: UserAccount) {
    const deployHash = await this.errors.capture(
      this.faucetService.requestTokens(account.publicKeyBase64)
    );
    this.monitorFaucetRequest(account, deployHash);
  }

  @computed get faucetRequests() {
    return this._faucetRequests.get;
  }

  private monitorFaucetRequest(account: UserAccount, deployHash: DeployHash) {
    const request = { timestamp: new Date(), account, deployHash };
    const requests = this._faucetRequests.get.concat(request);
    this._faucetRequests.set(requests);
    // TODO: Start polling for the deploy status.
  }
}

// Record of a request we submitted.
export interface FaucetRequest {
  timestamp: Date;
  account: UserAccount;
  deployHash: DeployHash;
}

export default CasperContainer;
