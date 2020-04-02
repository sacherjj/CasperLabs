import { Rpc } from './rpc';
import { Request } from './tunnel';

export function registerClient(
  logMessages = false
) {
  // A unique message ID that is used to ensure responses are sent to the correct requests
  let _messageId = 0;
  let generateNewMessageId = () => ++_messageId;
  const PAGE_ID = Math.random() * Number.MAX_SAFE_INTEGER;

  const rpc = new Rpc({
    source: 'page',
    destination: 'background',
    logMessages,
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

  return function call<RESULT>(method: string, ...params: any[]) {
    return rpc.call<RESULT>(method, ...params);
  };
}
