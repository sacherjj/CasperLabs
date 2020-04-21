// Declared so we can get it from the `window` object.

interface CasperLabsHelper {
  // whether CasperLabs Sign Helper Plugin is ready
  isConnected: () => boolean;
  // send base16 encoded message to plugin to sign
  sign: (messageBase16: string) => Promise<string>;
  // returns base64 encoded public key of user current selected account.
  getSelectedPublicKeyBase64: () => Promise<string | undefined>;
}

interface Window {
  origin: string;
  config: Config;
  casperlabsHelper?: CasperLabsHelper
}

interface Config {
  auth0: Auth0Config;
  graphql: {
    url?: string;
  };
  auth: {
    mock: {
      enabled: boolean;
    };
  };
  grpc: {
    url?: string;
  };
}

interface Auth0Config {
  domain: string;
  clientId: string;
}
