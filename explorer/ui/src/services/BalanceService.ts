import { Key } from '../grpc/io/casperlabs/casper/consensus/state_pb';
import { encodeBase16 } from '../lib/Conversions';
import CasperService from './CasperService';

/** Cache balance URef values for accounts so that on subsequent queries
 *  it only takes 1 state query not 4 to get the value.
 */
export class BalanceService {
  private balanceUrefs = new Map<string, Key.URef>();

  constructor(private casperService: CasperService) {}

  async getAccountBalance(
    blockHash: BlockHash,
    accountPublicKey: ByteArray
  ): Promise<number | undefined> {
    const hash = encodeBase16(accountPublicKey);
    let balanceUref = this.balanceUrefs.get(hash);

    // Find the balance Uref and cache it if we don't have it.
    if (!balanceUref) {
      balanceUref = await this.casperService.getAccountBalanceUref(
        blockHash,
        accountPublicKey
      );
      if (balanceUref) {
        this.balanceUrefs.set(hash, balanceUref);
      }
    }

    if (!balanceUref) return undefined;

    return await this.casperService.getAccountBalance(blockHash, balanceUref);
  }
}

export default BalanceService;
