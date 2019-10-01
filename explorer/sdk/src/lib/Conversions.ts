import { decodeBase64 } from 'tweetnacl-util';
import { ByteArray } from '../index';

// https://nodejs.org/api/buffer.html

export {encodeBase64, decodeBase64} from 'tweetnacl-util';

export function base64to16(base64: string): string {
  return encodeBase16(decodeBase64(base64));
}

export function encodeBase16(bytes: ByteArray): string {
  return Buffer.from(bytes).toString('hex');
}

export function decodeBase16(hex: string): ByteArray {
  return Buffer.from(hex, 'hex');
}
