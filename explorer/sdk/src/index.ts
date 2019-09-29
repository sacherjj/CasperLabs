import * as Args from './lib/Args';
import * as Contracts from './lib/Contracts';
import * as Keys from './lib/Keys';
import * as Serialization from './lib/Serialization';

export type ByteArray = Uint8Array;
export type DeployHash = ByteArray;
export type BlockHash = ByteArray;

export { CasperService, BalanceService, GrpcError } from './service';
export { Args, Contracts, Keys, Serialization};
export {encodeBase64, decodeBase64} from 'tweetnacl-util';
export {base64to16, encodeBase16, decodeBase16} from './lib/Conversions'
