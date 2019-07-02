import { observable } from 'mobx';
import createAuth0Client from '@auth0/auth0-spa-js';
import Auth0Client from '@auth0/auth0-spa-js/dist/typings/Auth0Client';
import ErrorContainer from './ErrorContainer';

// https://github.com/auth0/auth0-spa-js/issues/41
// https://auth0.com/docs/quickstart/spa/vanillajs
// https://auth0.com/docs/quickstart/spa/react

export class AuthContainer {
  @observable user: User | null = null;
  @observable accounts: Account[] | null = null;

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

    const isAuthenticated = await this.auth0!.isAuthenticated();
    this.user = isAuthenticated ? await this.auth0!.getUser() : null;
  }

  private async connect() {
    return await createAuth0Client({
      domain: this.conf.domain,
      client_id: this.conf.clientId,
      display: 'popup',
      redirect_uri: window.location.origin
    });
  }

  async login() {
    const isAuthenticated = await this.auth0!.isAuthenticated();
    if (!isAuthenticated) {
      await this.auth0!.loginWithPopup({});
    }
    this.user = await this.auth0!.getUser();
  }

  async logout() {
    this.auth0!.logout({ returnTo: window.location.origin });
    this.user = null;
  }

  refreshAccounts() {
    // this.exec(this.service.listAccounts(), (xs) => {
    //   this.accounts = xs;
    // })
  }
}

export default AuthContainer;
