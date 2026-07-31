import { generateKeyPairSync, randomBytes } from 'node:crypto';
import {
  chmodSync,
  closeSync,
  constants as fsConstants,
  fsyncSync,
  lstatSync,
  mkdirSync,
  openSync,
  realpathSync,
  unlinkSync,
  writeFileSync,
} from 'node:fs';
import { resolve } from 'node:path';

const KEY_ID_PATTERN = /^[A-Za-z0-9._-]{1,128}$/;
const PRIVATE_KEY_FILE_NAME = 'round-signing-key.pem';
const JWK_SET_FILE_NAME = 'round-jwks.json';

function fail(message) {
  process.stderr.write(`[round-key-material] ${message}\n`);
  process.exitCode = 1;
}

function writeExclusive(path, content) {
  const noFollow = fsConstants.O_NOFOLLOW ?? 0;
  let descriptor;
  let created = false;
  try {
    descriptor = openSync(
      path,
      fsConstants.O_WRONLY
        | fsConstants.O_CREAT
        | fsConstants.O_EXCL
        | noFollow,
      0o600,
    );
    created = true;
    writeFileSync(descriptor, content, { encoding: 'utf8' });
    fsyncSync(descriptor);
    closeSync(descriptor);
    descriptor = undefined;
    chmodSync(path, 0o600);
  } catch (error) {
    if (descriptor !== undefined) {
      try {
        closeSync(descriptor);
      } catch {
        // The original file operation failure remains the actionable error.
      }
    }
    if (created) {
      try {
        unlinkSync(path);
      } catch {
        // The original file operation failure remains the actionable error.
      }
    }
    throw error;
  }
}

function main() {
  const [outputDirectoryArgument, requestedKeyId, ...unexpectedArguments] =
    process.argv.slice(2);
  if (!outputDirectoryArgument || unexpectedArguments.length > 0) {
    throw new Error(
      '사용법: node generate-round-key-material.mjs <output-dir> [kid]',
    );
  }

  const keyId = requestedKeyId
    ?? `baton-round-e2e-${randomBytes(8).toString('hex')}`;
  if (!KEY_ID_PATTERN.test(keyId)) {
    throw new Error('kid는 영숫자와 ._-만 사용하는 1~128자여야 합니다');
  }

  process.umask(0o077);
  const outputDirectory = resolve(outputDirectoryArgument);
  mkdirSync(outputDirectory, { recursive: true, mode: 0o700 });

  const directoryStatus = lstatSync(outputDirectory);
  if (!directoryStatus.isDirectory() || directoryStatus.isSymbolicLink()) {
    throw new Error('output-dir은 심볼릭 링크가 아닌 디렉터리여야 합니다');
  }
  chmodSync(outputDirectory, 0o700);
  const canonicalOutputDirectory = realpathSync(outputDirectory);

  const privateKeyPath = resolve(
    canonicalOutputDirectory,
    PRIVATE_KEY_FILE_NAME,
  );
  const jwkSetPath = resolve(canonicalOutputDirectory, JWK_SET_FILE_NAME);

  const { privateKey, publicKey } = generateKeyPairSync('rsa', {
    modulusLength: 2048,
    publicExponent: 0x10001,
  });
  const privateKeyPem = privateKey.export({
    type: 'pkcs8',
    format: 'pem',
    cipher: undefined,
  });
  const exportedPublicKey = publicKey.export({ format: 'jwk' });
  if (
    exportedPublicKey.kty !== 'RSA'
    || typeof exportedPublicKey.n !== 'string'
    || typeof exportedPublicKey.e !== 'string'
  ) {
    throw new Error('생성한 RSA 공개키를 JWK로 변환하지 못했습니다');
  }

  const publicJwk = {
    kty: 'RSA',
    kid: keyId,
    use: 'sig',
    alg: 'RS256',
    n: exportedPublicKey.n,
    e: exportedPublicKey.e,
  };
  const jwkSet = `${JSON.stringify({ keys: [publicJwk] }, null, 2)}\n`;

  let privateKeyCreated = false;
  try {
    writeExclusive(privateKeyPath, privateKeyPem);
    privateKeyCreated = true;
    writeExclusive(jwkSetPath, jwkSet);
  } catch (error) {
    if (privateKeyCreated) {
      try {
        unlinkSync(privateKeyPath);
      } catch {
        // The original write failure remains the actionable error.
      }
    }
    throw error;
  }

  process.stdout.write(`${JSON.stringify({
    status: 'ready',
    kid: keyId,
    outputDirectory: canonicalOutputDirectory,
    privateKeyPath,
    jwkSetPath,
    algorithm: 'RS256',
    privateKeyFormat: 'PKCS8',
    modulusLength: 2048,
  })}\n`);
}

try {
  main();
} catch (error) {
  fail(error instanceof Error ? error.message : '키 생성에 실패했습니다');
}
