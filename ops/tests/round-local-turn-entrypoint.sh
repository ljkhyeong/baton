#!/bin/sh

set -eu

fail() {
  printf 'round-local-turn: %s\n' "$*" >&2
  exit 1
}

secret_file=/run/secrets/round-local-turn-shared-secret
[ -f "$secret_file" ] && [ ! -L "$secret_file" ] && [ -r "$secret_file" ] \
  || fail 'TURN shared secret file is not a readable regular file'

turn_shared_secret="$(cat "$secret_file")"
case "$turn_shared_secret" in
  ''|*[!A-Fa-f0-9]*) fail 'TURN shared secret must be hexadecimal' ;;
esac
[ "${#turn_shared_secret}" -ge 64 ] \
  || fail 'TURN shared secret must contain at least 64 hexadecimal characters'

runtime_config=/tmp/round-local-turnserver.conf
umask 077
{
  printf '%s\n' \
    'listening-ip=0.0.0.0' \
    'listening-port=3478' \
    'min-port=49160' \
    'max-port=49169' \
    'realm=baton.localhost' \
    'server-name=baton.localhost' \
    'fingerprint' \
    'use-auth-secret' \
    'stale-nonce=600' \
    'no-cli' \
    'no-tls' \
    'no-dtls' \
    'no-multicast-peers' \
    'user-quota=20' \
    'total-quota=100' \
    'pidfile=/tmp/turnserver.pid' \
    'userdb=/tmp/turndb' \
    'proc-user=nobody' \
    'proc-group=nogroup' \
    'log-file=stdout' \
    'simple-log'
  printf 'static-auth-secret=%s\n' "$turn_shared_secret"
} >"$runtime_config"
chmod 0600 "$runtime_config"
unset turn_shared_secret

exec /usr/bin/turnserver -c "$runtime_config"
