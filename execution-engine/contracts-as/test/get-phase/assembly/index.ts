import * as CL from "../../../../contract-as/assembly";
import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {fromBytesU8} from "../../../../contract-as/assembly/bytesrepr";

export function call(): void {
  const phaseBytes = CL.getArg(0);
  if (phaseBytes === null) {
    Error.fromErrorCode(ErrorCode.MissingArgument).revert();
    return;
  }
  if (phaseBytes.length != 1) {
    Error.fromErrorCode(ErrorCode.InvalidArgument).revert();
    return;
  }

  const phase = <CL.Phase>phaseBytes[0];

  const caller = CL.getPhase();
  assert(<u8>phase == <u8>caller);
}
