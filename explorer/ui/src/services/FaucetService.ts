import { decodeBase16 } from '../lib/Conversions';
import Auth0Service from './Auth0Service';

/** Call the API on the server backend. */
export default class FaucetService {
  constructor(private auth0Service: Auth0Service) {}

  async requestTokens(accountPublicKeyBase64: string): Promise<DeployHash> {
    const token = await this.auth0Service.getToken();
    const response = await fetch('/api/faucet', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`
      },
      body: JSON.stringify({ accountPublicKeyBase64 })
    });
    const json = await response.json();

    if (json.error) {
      throw new Error(json.error);
    }

    return decodeBase16(json.deployHashBase16);
  }
}
