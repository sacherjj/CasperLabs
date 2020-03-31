//@ts-nocheck
import * as CL from "../../../../contract-as/assembly";
import {Error} from "../../../../contract-as/assembly/error";
import {fromBytesString} from "../../../../contract-as/assembly/bytesrepr";
import {Key} from "../../../../contract-as/assembly/key";
import {putKey} from "../../../../contract-as/assembly";
import {createPurse} from "../../../../contract-as/assembly/purse";
import {URef} from "../../../../contract-as/assembly/uref";

enum Args {
  PurseName = 0,
}

enum CustomError {
  MissingPurseNameArg = 1,
  InvalidPurseNameArg = 2
}

export function call(): void {
  // purse name arg
  const purseNameArg = CL.getArg(Args.PurseName);
  if (purseNameArg === null) {
    Error.fromUserError(<u16>CustomError.MissingPurseNameArg).revert();
    return;
  }
  const purseNameResult = fromBytesString(purseNameArg);
  if (purseNameResult.hasError()) {
    Error.fromUserError(<u16>CustomError.InvalidPurseNameArg).revert();
    return;
  }
  let purseName = purseNameResult.value;

  const purse = createPurse();

  const key = Key.fromURef(purse);
  putKey(purseName, <Key>key);
}
