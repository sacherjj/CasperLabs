/**
 * Provide methods to communicate with [CasperLabs Signer Extension](https://github.com/CasperLabs/signer).
 * Works only on browser.
 *
 * @packageDocumentation
 */

/**
 * Check whether CasperLabs Signer extension is ready
 */
export const isConnected: () => boolean = () => {
  return !!window?.casperlabsHelper?.isConnected();
};

/**
 * Return base64 encoded public key of user current selected account.
 *
 * @throws Error if haven't connected to CasperLabs Signer browser extension.
 */
export const getSelectedPublicKeyBase64: () => Promise<string | undefined> = () => {
  throwIfNotConnected();
  return window.casperlabsHelper!.getSelectedPublicKeyBase64();
};

/**
 * Send base16 encoded message to plugin to sign
 *
 * @throws Error if haven't connected to CasperLabs Signer browser extension.
 */
export const sign: (messageBase16: string) => Promise<string> = (messageBase16: string) => {
  throwIfNotConnected();
  return window.casperlabsHelper!.sign(messageBase16);
};

const throwIfNotConnected = () => {
  if (!isConnected()) {
    throw new Error('No CasperLabs Signer browser plugin detected or it is not ready');
  }
};
