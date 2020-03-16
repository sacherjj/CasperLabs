import { WindowPostMessageProxy } from '@ont-community/window-post-message-proxy';
import { browser } from 'webextension-polyfill-ts';
import { AccountApi } from './api';
import { MethodType, Rpc } from './rpc';

let rpc: Rpc;

export function registerContentProxy(
  {
    logMessages = false,
    logWarnings = false
  }: {
    logMessages?: boolean;
    logWarnings?: boolean;
  }
) {
  const windowPostMessageProxy = new WindowPostMessageProxy({
    logMessages,
    suppressWarnings: !logWarnings,
    name: 'content-script',
    target: 'page'
  });

  windowPostMessageProxy.addHandler({
    handle: (msg) => browser.runtime.sendMessage(msg),
    test: (msg) => msg.type === 'casperlabs-plugin' && msg.source === 'page'
  });
}

export function registerProvider({ provider, logMessages }: { provider: AccountApi; logMessages: boolean }) {
  rpc = new Rpc({
    source: 'background',
    destination: 'page',
    logMessages,
    addListener: browser.runtime.onMessage.addListener
  });

  function checkedRegister(name: string, method: MethodType | undefined) {
    if (method === undefined) {
      throw new Error('DApi provider does not implement ' + name);
    }

    rpc.register(name, method);
  }

  checkedRegister('account.unlock', provider.unlock);
  console.log("register successfully")
}
