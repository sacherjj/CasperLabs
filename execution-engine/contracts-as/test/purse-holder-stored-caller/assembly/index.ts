//@ts-nocheck
import * as CL from "../../../../contract-as/assembly";
import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {fromBytesString} from "../../../../contract-as/assembly/bytesrepr";
import {Key} from "../../../../contract-as/assembly/key";
import {putKey} from "../../../../contract-as/assembly";
import {CLValue} from "../../../../contract-as/assembly/clvalue";
import {URef} from "../../../../contract-as/assembly/uref";

const METHOD_VERSION = "version";

enum Args {
  PurseHolderURef = 0,
  MethodName = 1,
  PurseName = 2,
}

enum CustomError {
  MissingPurseHolderURefArg = 0,
  InvalidPurseHolderURefArg = 1,
  MissingMethodNameArg = 2,
  InvalidMethodNameArg = 3,
  MissingPurseNameArg = 4,
  InvalidPurseNameArg = 5,
  UnableToGetVersion = 6,
  UnableToStoreVersion = 7,
  InvalidVersion = 8
}

export function call(): void {
  let urefBytes = CL.getArg(Args.PurseHolderURef);
  if (urefBytes === null) {
    Error.fromUserError(<u16>CustomError.MissingPurseHolderURefArg).revert();
    return;
  }
  let urefResult = URef.fromBytes(urefBytes);
  if (urefResult.hasError()) {
    Error.fromErrorCode(ErrorCode.InvalidArgument).revert();
    return;
  }
  let uref = urefResult.value;
  if (uref.isValid() == false){
    Error.fromUserError(<u16>CustomError.InvalidPurseHolderURefArg).revert();
    return;
  }
  const methodNameArg = CL.getArg(Args.MethodName);
  if (methodNameArg === null) {
    Error.fromUserError(<u16>CustomError.MissingMethodNameArg).revert();
    return;
  }
  const methodNameResult = fromBytesString(methodNameArg);
  if (methodNameResult.hasError()) {
    Error.fromUserError(<u16>CustomError.InvalidMethodNameArg).revert();
    return;
  }
  let methodName = methodNameResult.value;

  let key = Key.fromURef(uref);

  // short circuit if VERSION method called
  if (methodName == METHOD_VERSION){
    const args: CLValue[] = [
      CLValue.fromString(METHOD_VERSION)
    ];
    const versionBytes = CL.callContract(key, args);
    if (versionBytes === null) {
      Error.fromUserError(<u16>CustomError.UnableToGetVersion).revert();
      return;
    }
    const versionResult = fromBytesString(versionBytes);
    if (versionResult.hasError()) {
      Error.fromUserError(<u16>CustomError.InvalidVersion).revert();
      return;
    }
    let version = versionResult.value;
    const maybeVersionKey = Key.create(CLValue.fromString(version));
    if (maybeVersionKey === null) {
      Error.fromUserError(<u16>CustomError.UnableToStoreVersion).revert();
      return;
    }
    const versionKey = <Key>maybeVersionKey;
    putKey(METHOD_VERSION, <Key>versionKey);
    return;
  }

  const purseNameArg = CL.getArg(Args.PurseName);
  if (purseNameArg === null) {
    Error.fromUserError(<u16>CustomError.MissingPurseNameArg).revert();
    return;
  }
  const purseNameResult = fromBytesString(purseNameArg);
  if (purseNameResult.hasError()){
    Error.fromUserError(<u16>CustomError.InvalidPurseNameArg).revert();
    return;
  }
  const purseName = purseNameResult.value;

  const args: CLValue[] = [
    CLValue.fromString(methodName),
    CLValue.fromString(purseName)
  ];
  CL.callContract(key, args);
}
