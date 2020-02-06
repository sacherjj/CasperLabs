//@ts-nocheck
import * as CL from "../../../../contract-as/assembly";
import {Error} from "../../../../contract-as/assembly/error";
import {U512} from "../../../../contract-as/assembly/bignum";
import {fromBytesString} from "../../../../contract-as/assembly/bytesrepr";

const EXPECTED_STRING =  "Hello, world!";
const EXPECTED_NUM = 42;

enum Arg{
  String = 0,
  U512 = 1
}

enum CustomError {
  MissingArgument0 = 0,
  MissingArgument1 = 1,
  InvalidArgument0 = 2,
  InvalidArgument1 = 3,
}

export function call(): void {
  const stringArg = CL.getArg(Arg.String);
  if (stringArg === null) {
    Error.fromUserError(<u16>CustomError.MissingArgument0).revert();
    return;
  }
  const stringVal = fromBytesString(stringArg)
  if (stringVal != EXPECTED_STRING){
    Error.fromUserError(<u16>CustomError.InvalidArgument0).revert();
    return;
  }
  const u512Arg = CL.getArg(Arg.U512);
  if (u512Arg === null) {
    Error.fromUserError(<u16>CustomError.MissingArgument1).revert();
    return;
  }
  const u512Val = U512.fromBytes(u512Arg);
  if (u512Val != U512.fromU64(EXPECTED_NUM)){
    Error.fromUserError(<u16>CustomError.InvalidArgument1).revert();
    return;
  }
}
