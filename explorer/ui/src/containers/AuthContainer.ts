import { observable } from 'mobx';
import createAuth0Client from '@auth0/auth0-spa-js';
import Auth0Client from '@auth0/auth0-spa-js/dist/typings/Auth0Client';
import ErrorContainer from './ErrorContainer';
import FormData from './FormData';
import * as nacl from 'tweetnacl-ts';
import { encodeBase64 } from 'tweetnacl-util';
import { saveAs } from 'file-saver';

// https://github.com/auth0/auth0-spa-js/issues/41
// https://auth0.com/docs/quickstart/spa/vanillajs
// https://auth0.com/docs/quickstart/spa/react
// https://auth0.com/docs/api/management/v2/get-access-tokens-for-spas
// https://auth0.com/docs/api/management/v2#!/Users/patch_users_by_id
// https://www.npmjs.com/package/tweetnacl-ts#signatures
// https://tweetnacl.js.org/#/sign

const Auth0ApiUrl = 'https://casperlabs.auth0.com/api/v2/';

export class AuthContainer {
  @observable user: User | null = null;
  @observable accounts: UserAccount[] | null = null;

  // An account we are creating, while we're configuring it.
  @observable newAccount: NewAccountFormData | null = null;

  private auth0: Auth0Client | null = null;

  constructor(private conf: Auth0Config, private errors: ErrorContainer) {
    this.init();
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

  private async getAuth0() {
    if (this.auth0 != null) return this.auth0;

    const auth0 = await createAuth0Client({
      domain: this.conf.domain,
      client_id: this.conf.clientId,
      redirect_uri: window.location.origin,
      // This is needed so that we can query and update the `user_metadata` from here.
      audience: Auth0ApiUrl,
      scope:
        'read:current_user, create:current_user_metadata, update:current_user_metadata'
    });

    this.auth0 = auth0;
    return auth0;
  }

  async login() {
    const auth0 = await this.getAuth0();
    const isAuthenticated = await auth0.isAuthenticated();
    if (!isAuthenticated) {
      await this.auth0!.loginWithPopup({
        response_type: 'token id_token'
      } as PopupLoginOptions);
    }
    this.fetchUser();
  }

  async logout() {
    const auth0 = await this.getAuth0();
    auth0.logout({ returnTo: window.location.origin });
    this.user = null;
    this.accounts = null;
  }

  private async fetchUser() {
    const auth0 = await this.getAuth0();
    const isAuthenticated = await auth0.isAuthenticated();
    this.user = isAuthenticated ? await auth0.getUser() : null;
    this.refreshAccounts();
  }

  async refreshAccounts() {
    if (this.user != null) {
      const auth0 = await this.getAuth0();
      const token = await auth0.getTokenSilently();
      const response = await fetch(
        `${Auth0ApiUrl}users/${this.user.sub}?fields=user_metadata`,
        { headers: { Authorization: `Bearer ${token}` } }
      );

      const fields = await response.json();
      const meta: UserMetadata = fields.user_metadata || {};
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
      saveToFile(form.privateKey, `${form.name}.private.key`);
      saveToFile(form.publicKey, `${form.name}.public.key`);
      // Add the public key to the accounts and save it to Auth0.
      await this.addAccount({ name: form.name, publicKey: form.publicKey });
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
    this.accounts = this.accounts!.concat(account);
    await this.errors.capture(this.saveAccounts());
  }

  private async saveAccounts() {
    const userMetadata = {
      user_metadata: { accounts: this.accounts }
    };
    const auth0 = await this.getAuth0();
    const token = await auth0.getTokenSilently();
    const response = await fetch(`${Auth0ApiUrl}users/${this.user!.sub}`, {
      method: 'PATCH',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(userMetadata)
    });
    await response.json();
  }
}

function saveToFile(content: string, filename: string) {
  let blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
  saveAs(blob, filename);
}

export class NewAccountFormData extends FormData {
  constructor(private accounts: UserAccount[]) {
    super();
    // Generate key pair and assign to public and private keys.
    const keys = nacl.sign_keyPair();
    this.publicKey = encodeBase64(keys.publicKey);
    this.privateKey = encodeBase64(keys.secretKey);
  }

  @observable name: string = '';
  @observable publicKey: string = '';
  @observable privateKey: string = '';

  protected check() {
    if (this.name === '') return 'Name cannot be empty!';

    if (this.accounts.some(x => x.name === this.name))
      return `An account with name '${this.name}' already exists.`;

    if (this.accounts.some(x => x.publicKey === this.publicKey))
      return 'An account with this public key already exists.';

    return null;
  }
}

export default AuthContainer;
