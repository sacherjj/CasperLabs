import AccountController from './AuthController';
import { browser } from 'webextension-polyfill-ts';
import { Rpc } from '../lib/rpc/rpc';
import { AppState } from '../lib/MemStore';
import { autorun } from 'mobx';
import SignMessageManager, { SignMessage } from './lib/SignMessageManager';
import { PopupManager } from './PopupManager';
import * as nacl from 'tweetnacl-ts';


const appState = new AppState();
const accountController = new AccountController(appState);
const signMessageManager = new SignMessageManager(appState);
const popupManager = new PopupManager();

initialize().catch(console.error);

async function initialize() {
  await setupPopupAPIServer();
  // popupManager = new PopupManager();
  // popupManager.show();
  await setupInjectPageAPIServer();
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
  });
  rpc.register('account.unlock', accountController.unlock.bind(accountController));
  rpc.register('account.createNewVault', accountController.createNewVault.bind(accountController));
  rpc.register('account.hasCreatedVault', accountController.hasCreatedVault.bind(accountController));
  rpc.register('account.lock', accountController.lock.bind(accountController));
  rpc.register('account.importUserAccount', accountController.importUserAccount.bind(accountController));
  rpc.register('account.switchToAccount', accountController.switchToAccount.bind(accountController));
  rpc.register('background.getState', () => {
    return appState;
  });
  rpc.register('sign.signMessage', (msg: SignMessage) => {
    let sig = nacl.sign_detached(nacl.decodeBase64(msg.data), appState.userAccounts[0].signKeyPair.secretKey);
    return signMessageManager.setMsgStatusSigned(msg.id, nacl.encodeBase64(sig));
  });
  rpc.register('sign.rejectMessage', signMessageManager.rejectMsg.bind(signMessageManager));
}

// Setup RPC server for inject page
async function setupInjectPageAPIServer() {
  const rpc = new Rpc({
    addListener: browser.runtime.onMessageExternal.addListener,
    destination: 'page',
    source: 'background'
  });
  rpc.register('sign', function(message: string) {
    const promise = signMessageManager.addUnsignedMessageAsync(message);
    popupManager.show();
    return promise;
  });
}
