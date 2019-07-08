interface UserAccount {
  // Human readable name.
  name: string;
  // Public key in PEM format.
  publicKeyBase64: string;
}

interface UserMetadata {
  accounts?: UserAccount[];
}

interface User {
  // The User ID in Auth0.
  sub: string;
  name: string;
  email?: string;
}

type ByteArray = Uint8Array;
type DeployHash = ByteArray;
