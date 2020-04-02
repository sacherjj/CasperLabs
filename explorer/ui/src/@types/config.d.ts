// Declared so we can get it from the `window` object.

interface CasperLabsHelper {
  isConnected: () => boolean;
  sign: (string) => Promise<string>;
  getSelectedPublicKey: () => Promise<string | undefined>;
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
