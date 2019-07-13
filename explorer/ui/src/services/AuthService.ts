import Auth0Client from '@auth0/auth0-spa-js/dist/typings/Auth0Client';
import createAuth0Client from '@auth0/auth0-spa-js';

// https://github.com/auth0/auth0-spa-js/issues/41
// https://auth0.com/docs/quickstart/spa/vanillajs
// https://auth0.com/docs/quickstart/spa/react
// https://auth0.com/docs/api/management/v2/get-access-tokens-for-spas
// https://auth0.com/docs/api/management/v2#!/Users/patch_users_by_id

export interface RedirectState {
  appState?: {
    targetUrl?: string;
  };
}

/** Encapsulate user related operations so we can mock them in offline mode. */
export default interface AuthService {
  /* Get the JWT token we can send to the backend to prove the user is logged in. */
  getToken(): Promise<string>;

  getUserMetadata(userId: string): Promise<UserMetadata>;

  updateUserMetadata(userId: string, meta: UserMetadata): Promise<void>;

  isAuthenticated(): Promise<Boolean>;

  /** Show the login window. */
  login(): Promise<void>;

  logout(): void;

  /** Handle OAuth redirects and return application state. */
  handleRedirectCallback(): Promise<RedirectState>;

  getUser(): Promise<User>;
}

/** This is our `audience` value when we want to interact with the user managmeent API. */
export const Auth0ApiUrl = 'https://casperlabs.auth0.com/api/v2/';

/** Just a wrapper around Auth0Client. */
export class Auth0Service implements AuthService {
  private auth0: Auth0Client | null = null;

  constructor(private conf: Auth0Config) {}

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

  /* Get the JWT token we can send to the backend to prove the user is logged in. */
  async getToken() {
    const auth0 = await this.getAuth0();
    const token = await auth0.getTokenSilently();
    return token;
  }

  async getUserMetadata(userId: string): Promise<UserMetadata> {
    const token = await this.getToken();
    const response = await fetch(
      `${Auth0ApiUrl}users/${userId}?fields=user_metadata`,
      { headers: { Authorization: `Bearer ${token}` } }
    );

    const fields = await response.json();
    const meta: UserMetadata = fields.user_metadata || {};

    return meta;
  }

  async updateUserMetadata(userId: string, meta: UserMetadata): Promise<void> {
    const userMetadata = {
      user_metadata: meta
    };
    const token = await this.getToken();
    const response = await fetch(`${Auth0ApiUrl}users/${userId}`, {
      method: 'PATCH',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(userMetadata)
    });
    await response.json();
  }

  async isAuthenticated() {
    const auth0 = await this.getAuth0();
    return await auth0.isAuthenticated();
  }

  async login() {
    const auth0 = await this.getAuth0();
    await auth0.loginWithPopup({
      // We want the token to be available.
      response_type: 'token id_token'
    } as PopupLoginOptions);
  }

  async logout() {
    const auth0 = await this.getAuth0();
    auth0.logout({ returnTo: window.location.origin });
  }

  /** Handle OAuth redirects and return application state. */
  async handleRedirectCallback() {
    const auth0 = await this.getAuth0();
    return await auth0.handleRedirectCallback();
  }

  async getUser() {
    const auth0 = await this.getAuth0();
    return await auth0.getUser();
  }
}
