// Help avoid typos in routing and constructing links.
export default class Pages {
  static readonly Home = '/';
  static readonly Accounts = '/accounts';
  static readonly Faucet = '/faucet';
  static readonly Explorer = '/explorer';
  static readonly Blocks = '/blocks';
  static readonly Block = '/blocks/:blockHashBase16';

  static readonly block = (blockHashBase16: string) =>
    `/blocks/${blockHashBase16}`;
}
