//@ts-nocheck
import * as CL from "../../../../contract-as/assembly";
import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {fromBytesString} from "../../../../contract-as/assembly/bytesrepr";
import {URef} from "../../../../contract-as/assembly/uref";
import {Key} from "../../../../contract-as/assembly/key";
import {CLValue} from "../../../../contract-as/assembly/clvalue";

enum Args {
  DoNothingURef = 0,
  PurseName = 1,
}

enum CustomError {
  MissingDoNothingURefArg = 100,
  MissingPurseNameArg = 101,
  InvalidDoNothingURefArg = 102,
  InvalidPurseNameArg = 103
}

export function call(): void {
  // uref arg
  let urefBytes = CL.getArg(Args.DoNothingURef);
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

  // purse name arg
  const purseNameArg = CL.getArg(Args.PurseName);
  if (purseNameArg === null) {
    Error.fromUserError(<u16>CustomError.MissingPurseNameArg).revert();
    return;
  }
  const purseName = fromBytesString(purseNameArg);
  if (purseName === null){
    Error.fromUserError(<u16>CustomError.InvalidPurseNameArg).revert();
    return;
  }

  let key = Key.fromURef(uref);

  const args: CLValue[] = [
    CLValue.fromString(purseName)
  ];

  CL.callContract(key, args);
}
