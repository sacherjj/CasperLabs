export class NoPreviousVaultError extends Error {
  constructor() {
    super('Cannot unlock without a previous vault.');
  }
}
