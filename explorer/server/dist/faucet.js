"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const fs_1 = __importDefault(require("fs"));
class Faucet {
    constructor(contractPath) {
        this.contractWasm = fs_1.default.readFileSync(contractPath);
    }
}
exports.default = Faucet;
//# sourceMappingURL=faucet.js.map