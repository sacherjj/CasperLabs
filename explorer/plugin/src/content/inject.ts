import { registerClient } from '../lib/rpc/client';

class CasperLabsPluginHelper {
  private readonly call: <RESULT>(method: string, ...params: any[]) => Promise<RESULT>;

  constructor() {
    this.call = registerClient();
  }

  isConnected() {
    return true;
  }

  async sign(message: Uint8Array) {
    return this.call<Uint8Array>('sign', message);
  }
}

// inject to window
(window as any).casperlabsHelper = new CasperLabsPluginHelper();
