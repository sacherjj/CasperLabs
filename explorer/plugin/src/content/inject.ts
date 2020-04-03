import { registerClient } from '../lib/rpc/client';

// See README.md for details
class CasperLabsPluginHelper {
  private readonly call: <RESULT>(method: string, ...params: any[]) => Promise<RESULT>;

  constructor() {
    this.call = registerClient();
  }

  isConnected() {
    return true;
  }

  async sign(message: String) {
    return this.call<string>('sign', message);
  }

  async getSelectedPublicKey(){
    return this.call<string>('getSelectedPublicKey');
  }
}

// inject to window, so that Clarity code could use it.
(window as any).casperlabsHelper = new CasperLabsPluginHelper();
