import * as CL from "../../../../contract-ffi-as/assembly";
import {Error, ErrorCode} from "../../../../contract-ffi-as/assembly/error";
import {fromBytesString, fromBytesI32} from "../../../../contract-ffi-as/assembly/bytesrepr";
import {Key} from "../../../../contract-ffi-as/assembly/key"
import {addAssociatedKey, AddKeyFailure, ActionType, setActionThreshold, SetThresholdFailure} from "../../../../contract-ffi-as/assembly/account";

export function call(): void {
  // This body intentionally left empty.
  let publicKey = new Array<u8>(32);
  publicKey.fill(123);

  const addResult = addAssociatedKey(publicKey, 100);
  switch (addResult) {
    case AddKeyFailure.DuplicateKey:
      break;
    case AddKeyFailure.Ok:
      break;
    default:
      Error.fromUserError(50).revert();
      break;
  }

  let keyManagementThresholdBytes = CL.getArg(0);
  if (keyManagementThresholdBytes === null) {
    Error.fromErrorCode(ErrorCode.MissingArgument).revert();
    return;
  }
  let keyManagementThreshold = keyManagementThresholdBytes[0];

  let deployThresholdBytes = CL.getArg(0);

  if (deployThresholdBytes === null) {
    Error.fromErrorCode(ErrorCode.MissingArgument).revert();
    return;
  }

  let deployThreshold = deployThresholdBytes[0];

  if (keyManagementThreshold != 0) {
    if (setActionThreshold(ActionType.KeyManagement, keyManagementThreshold) != SetThresholdFailure.Ok) {
      // TODO: Create standard Error from those enum values
      Error.fromUserError(4464 + 1).revert();
    }
  }
  if (deployThreshold != 0) {
    if (setActionThreshold(ActionType.Deployment, deployThreshold) != SetThresholdFailure.Ok) {
      Error.fromUserError(4464).revert();
      return;
    }
  }
  
}
