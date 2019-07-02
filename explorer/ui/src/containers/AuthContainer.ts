import { observable } from 'mobx';
import createAuth0Client from '@auth0/auth0-spa-js';
import Auth0Client from '@auth0/auth0-spa-js/dist/typings/Auth0Client';
import ErrorContainer from './ErrorContainer';
import FormData from './FormData';

// https://github.com/auth0/auth0-spa-js/issues/41
// https://auth0.com/docs/quickstart/spa/vanillajs
// https://auth0.com/docs/quickstart/spa/react
// https://auth0.com/docs/api/management/v2/get-access-tokens-for-spas

const Auth0ApiUrl = 'https://casperlabs.auth0.com/api/v2/';

export class AuthContainer {
  @observable user: User | null = null;
  @observable accounts: Account[] | null = null;

  // An application we wish to start, while we're configuring it.
  @observable newAccount: NewAccountFormData | null = null;

  private auth0: Auth0Client | null = null;

  constructor(private conf: Auth0Config, private errors: ErrorContainer) {
    this.init();
  }

  private async init() {
    this.auth0 = await this.connect();

    if (window.location.search.includes('code=')) {
      const { appState } = await this.auth0!.handleRedirectCallback();
      const url =
        appState && appState.targetUrl
          ? appState.targetUrl
          : window.location.pathname;
      window.history.replaceState({}, document.title, url);
    }

    this.fetchUser();
  }

  private async connect() {
    return await createAuth0Client({
      domain: this.conf.domain,
      client_id: this.conf.clientId,
      redirect_uri: window.location.origin,
      // This is needed so that we can query and update the `user_metadata` from here.
      audience: Auth0ApiUrl,
      scope:
        'read:current_user, create:current_user_metadata, update:current_user_metadata'
    });
  }

  async login() {
    const isAuthenticated = await this.auth0!.isAuthenticated();
    if (!isAuthenticated) {
      await this.auth0!.loginWithPopup({
        response_type: 'token id_token'
      } as PopupLoginOptions);
    }
    this.fetchUser();
  }

  async logout() {
    this.auth0!.logout({ returnTo: window.location.origin });
    this.user = null;
    this.accounts = null;
  }

  private async fetchUser() {
    const isAuthenticated = await this.auth0!.isAuthenticated();
    this.user = isAuthenticated ? await this.auth0!.getUser() : null;
    this.refreshAccounts();
  }

  async refreshAccounts() {
    if (this.user != null) {
      // this.exec(this.service.listAccounts(), (xs) => {
      //   this.accounts = xs;
      // })
      const token = await this.auth0!.getTokenSilently();
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

  async createAccount() {
    alert('Creating...');
    return true;
  }
}

export class NewAccountFormData extends FormData {
  constructor(private accounts: Account[]) {
    super();
    // TODO: Generate key pair and assign to public and private keys.
  }

  @observable name: string | null = null;
  @observable publicKey: string | null = null;
  @observable privateKey: string | null = null;

  protected check() {
    if (this.name == null || this.name === '') return 'Name cannot be empty!';

    if (this.accounts.some(x => x.name === this.name))
      return `An account with name '$name' already exists.`;

    if (this.accounts.some(x => x.publicKey === this.publicKey))
      return 'An account with this public key already exists.';

    return null;
  }
}

export default AuthContainer;
