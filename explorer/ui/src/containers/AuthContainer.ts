import { observable } from 'mobx';
import createAuth0Client from '@auth0/auth0-spa-js';

// https://github.com/auth0/auth0-spa-js/issues/41
// https://auth0.com/docs/quickstart/spa/vanillajs
// https://auth0.com/docs/quickstart/spa/react

export class AuthContainer {
  conf: Auth0Config;
  @observable user: User | null = null;

  constructor(conf: Auth0Config) {
    this.conf = conf;
    this.init();
  }

  async init() {
    const auth0 = await this.connect();

    if (window.location.search.includes('code=')) {
      const { appState } = await auth0.handleRedirectCallback();
      const url =
        appState && appState.targetUrl
          ? appState.targetUrl
          : window.location.pathname;
      window.history.replaceState({}, document.title, url);
    }

    const isAuthenticated = await auth0.isAuthenticated();
    this.user = isAuthenticated ? await auth0.getUser() : null;
  }

  async connect() {
    return await createAuth0Client({
      domain: this.conf.domain,
      client_id: this.conf.clientId,
      display: 'popup',
      redirect_uri: window.location.origin
    });
  }

  async login() {
    const auth0 = await this.connect();
    const isAuthenticated = await auth0.isAuthenticated();
    if (!isAuthenticated) {
      await auth0.loginWithPopup({ display: 'popup' });
    }
    this.user = await auth0.getUser();
  }

  async logout() {
    const auth0 = await this.connect();
    auth0.logout({ returnTo: window.location.origin });
    this.user = null;
  }
}

export default AuthContainer;
