import * as CL from "../../../../contract-as/assembly";
import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {fromBytesU64} from "../../../../contract-as/assembly/bytesrepr";

export function call(): void {
  const knownBlockTimeBytes = CL.getArg(0);
  if (knownBlockTimeBytes === null) {
    Error.fromErrorCode(ErrorCode.MissingArgument).revert();
    return;
  }
  const knownBlockTime = fromBytesU64(knownBlockTimeBytes);
  if (knownBlockTime === null) {
    Error.fromErrorCode(ErrorCode.InvalidArgument).revert();
    return;
  }

  const blockTime = CL.getBlockTime();
  assert(blockTime == <u64>knownBlockTime);
}
