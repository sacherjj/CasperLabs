import AccountController from './AuthController';
import { browser } from 'webextension-polyfill-ts';
import { Rpc } from '../lib/rpc/rpc';
import { AppState } from '../lib/MemStore';
import { autorun } from 'mobx';
import SignMessageManager, { SignMessage } from './SignMessageManager';
import * as nacl from 'tweetnacl-ts';
import { updateBadge } from './utils';
import { setupInjectPageAPIServer } from '../lib/rpc/Provider';

const appState = new AppState();
const accountController = new AccountController(appState);
const signMessageManager = new SignMessageManager(appState);

initialize().catch(console.error);

async function initialize() {
  await setupPopupAPIServer();
  // Setup RPC server for inject page
  setupInjectPageAPIServer(signMessageManager);
}

// Setup RPC server for Popup
async function setupPopupAPIServer() {
  const rpc = new Rpc({
    addListener: browser.runtime.onMessage.addListener,
    destination: 'popup',
    postMessage: browser.runtime.sendMessage,
    source: 'background'
  });
  // once appState update, send updated appState to popup
  autorun(() => {
    rpc.call<void>('popup.updateState', appState);
    updateBadge(appState);
  });
  rpc.register(
    'account.unlock',
    accountController.unlock.bind(accountController)
  );
  rpc.register(
    'account.createNewVault',
    accountController.createNewVault.bind(accountController)
  );
  rpc.register('account.lock', accountController.lock.bind(accountController));
  rpc.register(
    'account.importUserAccount',
    accountController.importUserAccount.bind(accountController)
  );
  rpc.register(
    'account.switchToAccount',
    accountController.switchToAccount.bind(accountController)
  );
  rpc.register('background.getState', () => {
    return appState;
  });
  rpc.register('sign.signMessage', (msg: SignMessage) => {
    if (!appState.selectedUserAccount) {
      throw new Error(`Please select the account firstly`);
    }
    let sig = nacl.sign_detached(
      nacl.decodeBase64(msg.data),
      appState.selectedUserAccount.signKeyPair.secretKey
    );
    return signMessageManager.approveMsg(msg.id, nacl.encodeBase64(sig));
  });
  rpc.register(
    'sign.rejectMessage',
    signMessageManager.rejectMsg.bind(signMessageManager)
  );
}
