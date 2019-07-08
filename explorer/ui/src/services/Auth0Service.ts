import Auth0Client from '@auth0/auth0-spa-js/dist/typings/Auth0Client';
import createAuth0Client from '@auth0/auth0-spa-js';

export const Auth0ApiUrl = 'https://casperlabs.auth0.com/api/v2/';

/** Just a wrapper around Auth0Client. */
export default class Auth0Service {
  private auth0: Auth0Client | null = null;

  constructor(private conf: Auth0Config) {}

  async getAuth0() {
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
}
