import { Rpc } from '../lib/rpc/rpc';
import { Request } from '../lib/rpc/tunnel';


class CasperLabsPluginHelper {
  private rpc: Rpc;

  constructor() {
    this.rpc = new Rpc({
      source: 'page',
      destination: 'background',
      logMessages: true,
      postMessage: (msg: Request) => {
        return new Promise(resolve => {
          (window as any).chrome.runtime.sendMessage('gooknelikfcihebkccpmibdhlbccghap', msg, (result: any) => {
            resolve(result);
          });
        });
      }
    });
  }

  isConnected() {
    return true;
  }

  async sign(message: Uint8Array) {
    return this.rpc.call<Uint8Array>('sign', message);
  }
}

// inject to window
(window as any).casperlabsHelper = new CasperLabsPluginHelper();
