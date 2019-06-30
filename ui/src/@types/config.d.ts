// Declared so we can get it from the `window` object.
interface Window {
  origin: string;
}

// The authenticated user
interface Principal {
  username: string;
}

interface Account {
  alias: string;
  publicKey: string;
}
