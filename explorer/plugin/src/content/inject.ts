import { Rpc } from '../lib/rpc/rpc';
import { Request } from '../lib/rpc/tunnel';

// A unique message ID that is used to ensure responses are sent to the correct requests
let _messageId = 0;

let generateNewMessageId = () => ++_messageId;

const PAGE_ID = Math.random() * Number.MAX_SAFE_INTEGER;

class CasperLabsPluginHelper {
  private rpc: Rpc;

  constructor() {
    this.rpc = new Rpc({
      source: 'page',
      destination: 'background',
      logMessages: true,
      postMessage: (msg: Request) => {
        return new Promise((resolve, reject) => {
          const msgId = generateNewMessageId();
          window.postMessage({ type: 'request', pageId: PAGE_ID, msgId, payload: msg }, '*');

          let transact = (e: MessageEvent) => {
            if (e.data.pageId === PAGE_ID && e.data.msgId === msgId && e.data.type === 'reply') {
              window.removeEventListener('message', transact, false);
              resolve(e.data.value);
            }
          };

          window.addEventListener('message', transact, false);
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
