import { observable } from 'mobx';
import * as nacl from 'tweetnacl-ts';
import { saveAs } from 'file-saver';
import ErrorContainer from './ErrorContainer';
import FormData from './FormData';
import Auth0Service from '../services/Auth0Service';
import { encodeBase64 } from '../lib/Conversions';

// https://github.com/auth0/auth0-spa-js/issues/41
// https://auth0.com/docs/quickstart/spa/vanillajs
// https://auth0.com/docs/quickstart/spa/react
// https://auth0.com/docs/api/management/v2/get-access-tokens-for-spas
// https://auth0.com/docs/api/management/v2#!/Users/patch_users_by_id
// https://www.npmjs.com/package/tweetnacl-ts#signatures
// https://tweetnacl.js.org/#/sign

export class AuthContainer {
  @observable user: User | null = null;
  @observable accounts: UserAccount[] | null = null;

  // An account we are creating, while we're configuring it.
  @observable newAccount: NewAccountFormData | null = null;

  @observable selectedAccount: UserAccount | null = null;

  constructor(
    private errors: ErrorContainer,
    private auth0Service: Auth0Service
  ) {
    this.init();
  }

  private getAuth0() {
    return this.auth0Service.getAuth0();
  }

  private async init() {
    const auth0 = await this.getAuth0();

    if (window.location.search.includes('code=')) {
      const { appState } = await auth0.handleRedirectCallback();
      const url =
        appState && appState.targetUrl
          ? appState.targetUrl
          : window.location.pathname;
      window.history.replaceState({}, document.title, url);
    }

    this.fetchUser();
  }

  async login() {
    const auth0 = await this.getAuth0();
    const isAuthenticated = await auth0.isAuthenticated();
    if (!isAuthenticated) {
      await auth0.loginWithPopup({
        response_type: 'token id_token'
      } as PopupLoginOptions);
    }
    this.fetchUser();
  }

  async logout() {
    const auth0 = await this.getAuth0();
    this.user = null;
    this.accounts = null;
    sessionStorage.clear();
    auth0.logout({ returnTo: window.location.origin });
  }

  private async fetchUser() {
    const auth0 = await this.getAuth0();
    const isAuthenticated = await auth0.isAuthenticated();
    this.user = isAuthenticated ? await auth0.getUser() : null;
    this.refreshAccounts();
  }

  async refreshAccounts() {
    if (this.user != null) {
      const meta: UserMetadata = await this.auth0Service.getUserMetadata(
        this.user.sub
      );
      this.accounts = meta.accounts || [];
    }
  }

  // Open a new account creation form.
  configureNewAccount() {
    this.newAccount = new NewAccountFormData(this.accounts!);
  }

  async createAccount(): Promise<boolean> {
    let form = this.newAccount!;
    if (form.clean()) {
      // Save the private and public keys to disk.
      saveToFile(form.privateKeyBase64, `${form.name}.private.key`);
      saveToFile(form.publicKeyBase64, `${form.name}.public.key`);
      // Add the public key to the accounts and save it to Auth0.
      await this.addAccount({
        name: form.name,
        publicKeyBase64: form.publicKeyBase64
      });
      return true;
    } else {
      return false;
    }
  }

  async deleteAccount(name: String) {
    if (window.confirm(`Are you sure you want to delete account '${name}'?`)) {
      this.accounts = this.accounts!.filter(x => x.name !== name);
      await this.errors.capture(this.saveAccounts());
    }
  }

  private async addAccount(account: UserAccount) {
    this.accounts!.push(account);
    await this.errors.capture(this.saveAccounts());
  }

  private async saveAccounts() {
    await this.auth0Service.updateUserMetadata(this.user!.sub, {
      accounts: this.accounts || undefined
    });
  }

  selectAccountByName(name: string) {
    this.selectedAccount = this.accounts!.find(x => x.name === name) || null;
  }
}

function saveToFile(content: string, filename: string) {
  let blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
  saveAs(blob, filename);
}

class NewAccountFormData extends FormData {
  constructor(private accounts: UserAccount[]) {
    super();
    // Generate key pair and assign to public and private keys.
    const keys = nacl.sign_keyPair();
    this.publicKeyBase64 = encodeBase64(keys.publicKey);
    this.privateKeyBase64 = encodeBase64(keys.secretKey);
  }

  @observable name: string = '';
  @observable publicKeyBase64: string = '';
  @observable privateKeyBase64: string = '';

  protected check() {
    if (this.name === '') return 'Name cannot be empty!';

    if (this.accounts.some(x => x.name === this.name))
      return `An account with name '${this.name}' already exists.`;

    if (this.accounts.some(x => x.publicKeyBase64 === this.publicKeyBase64))
      return 'An account with this public key already exists.';

    return null;
  }
}

export default AuthContainer;
