import { browser } from 'webextension-polyfill-ts';
import { Rpc } from './rpc';
import SignMessageManager from '../../background/SignMessageManager';

// inject page -> content script -> background
export function registerContentProxy(
  logMessages = false
) {
  // forward messages from inpage to background
  window.addEventListener('message', receiveMessage, false);

  async function receiveMessage(event: MessageEvent) {
    const msg = event.data;
    if (logMessages) {
      console.log('receive message', msg);
    }
    // validate message type
    if (typeof msg !== 'object') return;
    if (msg.type !== 'request') return;
    msg.value = await browser.runtime.sendMessage(msg.payload);
    msg.type = 'reply';
    window.postMessage(msg, '*');
  }
}

let rpc: Rpc;

// Setup RPC server for inject page
export function setupInjectPageAPIServer(provider: SignMessageManager, logMessages: boolean = false) {
  rpc = new Rpc({
    addListener: browser.runtime.onMessage.addListener,
    logMessages,
    destination: 'page',
    source: 'background'
  });
  rpc.register('sign', provider.addUnsignedMessageAsync.bind(provider));
}
