import { action, computed } from 'mobx';
import passworder from 'browser-passworder';
import store from 'store';
import * as nacl from 'tweetnacl';
import { decodeBase64, encodeBase64 } from 'tweetnacl-util';
import { AppState } from '../lib/MemStore';

class AuthController {
  private encryptedVaultStr: string | null = null;
  private password: string | null = null;
  private encryptedVaultKey = 'encryptedVault';

  constructor(private appState: AppState) {
  }

  hasCreatedVault() {
    return this.encryptedVaultStr !== null;
  }

  @action.bound
  async createNewVault(password: string): Promise<void> {
    if (this.encryptedVaultStr) {
      throw new Error('There is a vault already');
    }
    const hash = this.hash(password);
    this.password = hash;
    await this.clearAccount();
    await this.persistVault(this.password!);
    this.appState.hasCreatedVault = true;
    this.appState.isUnlocked = true;
  }

  @action
  switchToAccount(account: SignKeyPairWithAlias) {
    let i = this.appState.userAccounts.findIndex(a => a.name === account.name);
    if (i === -1) {
      throw new Error('Couldn\'t switch to this account because it doesn\'t exist');
    }
    this.appState.selectedUserAccount = this.appState.userAccounts[i];
  }

  @action
  async importUserAccount(name: string, privateKey: string) {
    if (!this.appState.isUnlocked) {
      throw new Error('Unlock it before adding new account');
    }

    const keyPair = nacl.sign.keyPair.fromSecretKey(decodeBase64(privateKey));
    let account = this.appState.userAccounts.find(account => {
      return account.name === name || encodeBase64(account.signKeyPair.secretKey) === encodeBase64(keyPair.secretKey);
    });

    if (account) {
      throw new Error(`A account with same ${account.name === name ? 'name' : 'private key'} already exists`);
    }

    this.appState.userAccounts.push({
      name: name,
      signKeyPair: keyPair
    });
    if (this.appState.selectedUserAccount === null) {
      this.appState.selectedUserAccount = this.appState.userAccounts[0];
    }
    this.persistVault(this.password!);
  }

  private async persistVault(password: string) {
    const encryptedVault = await passworder.encrypt(password, this.appState.userAccounts);
    store.set(this.encryptedVaultKey, encryptedVault);
    this.encryptedVaultStr = encryptedVault; // for unlock in future
  }

  private async restoreVault(password: string) {
    if (!this.encryptedVaultStr) {
      throw new Error();
    }
    const vault = await passworder.decrypt(password, this.encryptedVaultStr);
    return vault as unknown as SignKeyPairWithAlias[];
  }

  /**
   * Hash the user input password for storing locally
   * @param str
   */
  private hash(str: string) {
    const b = new Buffer(str);
    const h = nacl.hash(Uint8Array.from(b));
    return encodeBase64(h);
  }

  /**
   * @param {string} password
   */
  @action.bound
  async unlock(password: string) {
    const vault = await this.restoreVault(this.hash(password));
    await this.clearAccount();
    this.appState.isUnlocked = true;
    this.appState.userAccounts.replace(vault);
  }

  // user can lock the plugin manually
  @action.bound
  async lock() {
    this.password = null;
    this.appState.isUnlocked = false;
    this.appState.userAccounts.clear();
  }

  @computed
  get isUnLocked(): boolean {
    return this.appState.isUnlocked;
  }

  @action.bound
  async clearAccount() {
    this.appState.userAccounts.clear();
  }
}

export default AuthController;
