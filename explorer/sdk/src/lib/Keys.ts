import * as fs from 'fs';
import * as nacl from 'tweetnacl-ts';
import { decodeBase64 } from 'tweetnacl-util';
import { ByteArray } from '../index';

// Based on SignatureAlgorithm.scala
export class Ed25519 {
  public static parseKeyFiles(
    publicKeyPath: string,
    privateKeyPath: string
  ): nacl.SignKeyPair {
    const publicKey = Ed25519.parsePublicKeyFile(publicKeyPath);
    const privateKey = Ed25519.parsePrivateKeyFile(privateKeyPath);
    // nacl expects that the private key will contain both.
    return {
      publicKey,
      secretKey: Buffer.concat([privateKey, publicKey])
    };
  }

  public static parseKeyPair(
    publicKey: ByteArray,
    privateKey: ByteArray
  ): nacl.SignKeyPair {
    const publ = Ed25519.parsePublicKey(publicKey);
    const priv = Ed25519.parsePrivateKey(privateKey);
    // nacl expects that the private key will contain both.
    return {
      publicKey: publ,
      secretKey: Buffer.concat([priv, publ])
    };
  }

  public static parsePrivateKeyFile(path: string): ByteArray {
    return Ed25519.parsePrivateKey(Ed25519.readBase64File(path));
  }

  public static parsePublicKeyFile(path: string): ByteArray {
    return Ed25519.parsePublicKey(Ed25519.readBase64File(path));
  }

  public static parsePrivateKey(bytes: ByteArray) {
    return Ed25519.parseKey(bytes, 0, 32);
  }

  public static parsePublicKey(bytes: ByteArray) {
    return Ed25519.parseKey(bytes, 32, 64);
  }

  /** Get rid of PEM frames */
  public static readBase64WithPEM(content: string): ByteArray {
    const base64 = content
      .split('\n')
      .filter(x => !x.startsWith('---'))
      .join('');
    const bytes = decodeBase64(base64);
    return bytes;
  }

  /** Read the Base64 content of a file, get rid of PEM frames. */
  private static readBase64File(path: string): ByteArray {
    const content = fs.readFileSync(path).toString();
    return Ed25519.readBase64WithPEM(content);
  }

  private static parseKey(bytes: ByteArray, from: number, to: number) {
    const len = bytes.length;
    // prettier-ignore
    const key =
      (len === 32) ? bytes :
        (len === 64) ? Buffer.from(bytes).slice(from, to) :
          (len > 32 && len < 64) ? Buffer.from(bytes).slice(len % 32) :
            null;
    if (key == null || key.length !== 32) {
      throw Error(`Unexpected key length: ${len}`);
    }
    return key;
  }
}
