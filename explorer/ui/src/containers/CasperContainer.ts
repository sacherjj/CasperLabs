import { computed, observable } from 'mobx';

import ErrorContainer from './ErrorContainer';
import StorageCell from '../lib/StorageCell';
import FaucetService from '../services/FaucetService';
import CasperService from '../services/CasperService';
import { DeployInfo } from '../grpc/io/casperlabs/casper/consensus/info_pb';
import { GrpcError } from '../services/Errors';
import { grpc } from '@improbable-eng/grpc-web';

// CasperContainer talks to the API on behalf of React
// components and exposes the state in MobX observables.
export class CasperContainer {
  private _faucetRequests = new StorageCell<FaucetRequest[]>(
    'faucet-requests',
    []
  );
  @observable deployInfos = new Map<DeployHash, DeployInfo>();

  constructor(
    private errors: ErrorContainer,
    private faucetService: FaucetService,
    private casperService: CasperService
  ) {}

  /** Ask the faucet for tokens for a given account. */
  async requestTokens(account: UserAccount) {
    const request = async () => {
      const deployHash = await this.faucetService.requestTokens(
        account.publicKeyBase64
      );
      this.monitorFaucetRequest(account, deployHash);
    };
    this.errors.capture(request());
  }

  /** List faucet requests we sent earlier. */
  @computed get faucetRequests() {
    return this._faucetRequests.get;
  }

  private monitorFaucetRequest(account: UserAccount, deployHash: DeployHash) {
    const request = { timestamp: new Date(), account, deployHash };
    const requests = this._faucetRequests.get.concat(request);
    this._faucetRequests.set(requests);
    // TODO: Start polling for the deploy status.
  }

  async refreshFaucetRequestStatus() {
    for (let req of this._faucetRequests.get) {
      const status = this.deployInfos.get(req.deployHash);
      const needsUpdate =
        typeof status === 'undefined' ||
        status!.getProcessingResultsList().findIndex(x => !x.getIsError()) ===
          -1;

      if (needsUpdate) {
        const info = await this.tryGetDeployInfo(req.deployHash);
        if (info != null) {
          this.deployInfos.set(req.deployHash, info);
        }
      }
    }
  }

  private async tryGetDeployInfo(
    deployHash: ByteArray
  ): Promise<DeployInfo | null> {
    try {
      return await this.casperService.getDeployInfo(deployHash);
    } catch (err) {
      if (err instanceof GrpcError) {
        if (err.code === grpc.Code.NotFound)
          // We connected to a node that doesn't have it yet.
          return null;
      }
      throw err;
    }
  }
}

// Record of a request we submitted.
export interface FaucetRequest {
  timestamp: Date;
  account: UserAccount;
  deployHash: DeployHash;
}

export default CasperContainer;
