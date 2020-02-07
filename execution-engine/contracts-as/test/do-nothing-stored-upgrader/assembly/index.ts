//@ts-nocheck
import * as CL from "../../../../contract-as/assembly";
import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {fromBytesString} from "../../../../contract-as/assembly/bytesrepr";
import {Key} from "../../../../contract-as/assembly/key";
import {putKey, upgradeContractAtURef} from "../../../../contract-as/assembly";
import {PurseId} from "../../../../contract-as/assembly/purseid";
import {URef} from "../../../../contract-as/assembly/uref";

const ENTRY_FUNCTION_NAME = "delegate";

enum CallArgs {
  DoNothingURef = 0,
}

enum DelegatedArgs {
  PurseName = 0,
}

enum CustomError {
  MissingDoNothingURefArg = 200,
  MissingPurseNameArg = 201,
  InvalidDoNothingURefArg = 202,
  InvalidPurseNameArg = 203,
  UnableToCreatePurse =204
}

export function delegate(): void {
  // purse name arg
  const purseNameArg = CL.getArg(DelegatedArgs.PurseName);
  if (purseNameArg === null) {
    Error.fromUserError(<u16>CustomError.MissingPurseNameArg).revert();
    return;
  }
  const purseName = fromBytesString(purseNameArg);
  if (purseName === null){
    Error.fromUserError(<u16>CustomError.InvalidPurseNameArg).revert();
    return;
  }

  const maybePurse = PurseId.create();
  if (maybePurse === null){
    Error.fromUserError(<u16>CustomError.UnableToCreatePurse).revert();
    return;
  }

  const key = Key.fromURef(maybePurse.asURef());

  putKey(purseName, <Key>key);
}

export function call(): void {
  let urefBytes = CL.getArg(CallArgs.DoNothingURef);
  if (urefBytes === null) {
    Error.fromUserError(<u16>CustomError.MissingDoNothingURefArg).revert();
    return;
  }
  let uref = URef.fromBytes(urefBytes);
  if (uref === null) {
    Error.fromErrorCode(ErrorCode.InvalidArgument).revert();
    return;
  }
  if (uref.isValid() == false){
    Error.fromUserError(<u16>CustomError.InvalidDoNothingURefArg).revert();
    return;
  }

  upgradeContractAtURef(ENTRY_FUNCTION_NAME, uref);
}
