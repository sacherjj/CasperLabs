//@ts-nocheck
import * as CL from "../../../../contract-as/assembly";
import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {fromBytesString} from "../../../../contract-as/assembly/bytesrepr";
import {URef} from "../../../../contract-as/assembly/uref";
import {Key} from "../../../../contract-as/assembly/key";
import {CLValue} from "../../../../contract-as/assembly/clvalue";
import {RuntimeArgs} from "../../../../contract-as/assembly/runtime_args";

const ENTRY_FUNCTION_NAME = "delegate";
const PURSE_NAME_ARG_NAME = "purse_name";
const ARG_CONTRACT_PACKAGE = "contract_package";
const ARG_NEW_PURSE_NAME = "new_purse_name";
const ARG_VERSION = "version";

export function call(): void {
  let contractPackageHash = CL.getNamedArg(ARG_CONTRACT_PACKAGE);
  const newPurseNameBytes = CL.getNamedArg(ARG_NEW_PURSE_NAME);
  const newPurseName = fromBytesString(newPurseNameBytes).unwrap();
  const versionNumber = CL.getNamedArg(ARG_VERSION)[0];

  let args = new Map<String, CLValue>();
  args.set(PURSE_NAME_ARG_NAME, CLValue.fromString(newPurseName));

  CL.callVersionedContract(contractPackageHash, versionNumber, ENTRY_FUNCTION_NAME, RuntimeArgs.fromMap(args));
}
