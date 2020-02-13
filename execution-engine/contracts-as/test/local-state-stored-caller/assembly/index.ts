//@ts-nocheck
import * as CL from "../../../../contract-as/assembly";
import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {URef} from "../../../../contract-as/assembly/uref";
import {Key} from "../../../../contract-as/assembly/key";
import {CLValue} from "../../../../contract-as/assembly/clvalue";

enum Args {
  LocalStateURef = 0,
}

enum CustomError {
  MissingURefArg = 1,
  InvalidURefArg = 2,
}

export function call(): void {
  let urefBytes = CL.getArg(Args.LocalStateURef);
  if (urefBytes === null) {
    Error.fromUserError(<u16>CustomError.MissingURefArg).revert();
    return;
  }
  let urefResult = URef.fromBytes(urefBytes);
  if (urefResult.hasError()) {
    Error.fromErrorCode(ErrorCode.InvalidArgument).revert();
    return;
  }
  let uref = urefResult.value;
  if (uref.isValid() == false){
    Error.fromUserError(<u16>CustomError.InvalidURefArg).revert();
    return;
  }

  let key = Key.fromURef(uref);
  const args: CLValue[] = [];

  CL.callContract(key, args);
}
