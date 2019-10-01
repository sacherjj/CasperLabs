import * as Args from './lib/Args';
import * as Contracts from './lib/Contracts';
import * as Keys from './lib/Keys';
import * as Serialization from './lib/Serialization';

export type ByteArray = Uint8Array;
export type DeployHash = ByteArray;
export type BlockHash = ByteArray;

export { CasperService, BalanceService, GrpcError } from './services';
export { Args, Contracts, Keys, Serialization};
export {base64to16, encodeBase16, decodeBase16, encodeBase64, decodeBase64} from './lib/Conversions'
