// TODO: do we want to port PurseId or not bother as it adds additional cruft for little value?
// import {UREF_SERIALIZED_LENGTH, URef} from "./uref";
// import * as externals from "./externals";
//
// export const PURSE_ID_SERIALIZED_LENGTH = UREF_SERIALIZED_LENGTH;
//
// export class PurseId {
//     private uref: URef;
//
//     constructor(uref: URef) {
//         this.uref = uref;
//     }
//
//     toBytes(): Array<u8>{
//         const bytes = this.uref.getBytes();
//         const len = bytes.length;
//         let result = new Array<u8>(len);
//         for (let i = 0; i < len; i++) {
//             result[i] = bytes[i];
//         }
//         return result;
//     }
//
//     static fromBytes(bytes: Uint8Array): PurseId | null {
//         let uref = URef.fromBytes(bytes);
//         if(uref === null)
//             return null;
//         return new PurseId(uref);
//     }
//
//     transferToPurse(target: PurseId, amount: Uint8Array): i32 {
//         let sourceBytes = this.uref.toBytes();
//         let targetBytes = target.toBytes();
//
//         let ret = externals.transfer_from_purse_to_purse(
//             sourceBytes.dataStart,
//             sourceBytes.length,
//             targetBytes.dataStart,
//             targetBytes.length,
//             // NOTE: amount has U512 type but is not deserialized throughout the execution, as there's no direct replacement for big ints
//             amount.dataStart,
//             amount.length,
//         );
//         return ret;
//     }
// }