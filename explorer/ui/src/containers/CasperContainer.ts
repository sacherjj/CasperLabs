import { computed, observable } from 'mobx';

import ErrorContainer from './ErrorContainer';
import StorageCell from '../lib/StorageCell';
import FaucetService from '../services/FaucetService';
import CasperService from '../services/CasperService';
import { DeployInfo } from '../grpc/io/casperlabs/casper/consensus/info_pb';
import { GrpcError } from '../services/Errors';
import { grpc } from '@improbable-eng/grpc-web';
import { encodeBase16 } from '../lib/Conversions';

// CasperContainer talks to the API on behalf of React
// components and exposes the state in MobX observables.
export class CasperContainer {
  private _faucetRequests = new StorageCell<FaucetRequest[]>(
    'faucet-requests',
    []
  );

  // Technical observable because the UI doesn't seem to react to
  // changes in the fields of the faucet request.
  @observable lastRefresh: Date | undefined = undefined;

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
    const requests = this._faucetRequests.get;
    let updated = false;
    for (let req of requests) {
      const needsUpdate =
        typeof req.deployInfo === 'undefined' ||
        req.deployInfo!.processingResultsList.length === 0;

      if (needsUpdate) {
        const info = await this.errors.withCapture(
          this.tryGetDeployInfo(req.deployHash)
        );
        if (info != null) {
          req.deployInfo = info.toObject();
          updated = true;
        }
      }
    }
    if (updated) {
      this._faucetRequests.set(requests);
    }
    this.lastRefresh = new Date();
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
  // Assigned later when the data becomes available.
  deployInfo?: DeployInfo.AsObject;
}

export default CasperContainer;
