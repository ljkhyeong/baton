#!/usr/bin/env bash

set -Eeuo pipefail
export LC_ALL=C

fail() {
  printf 'Production environment validation failed: %s\n' "$1" >&2
  exit 1
}

if [[ $# -ne 1 ]]; then
  printf 'Usage: %s /absolute/path/to/.env.production\n' "$0" >&2
  exit 1
fi
env_file="$1"
if [[ "$env_file" == *$'\n'* || "$env_file" == *$'\r'* ]]; then
  fail "production environment file path must not contain line breaks"
fi
case "$env_file" in
  /*) ;;
  *) fail "production environment file must be an absolute path: $env_file" ;;
esac

validate_file_boundary() {
  local target="$1"

  if [[ -L "$target" ]]; then
    fail "environment file must not be a symbolic link: $target"
  fi
  if [[ ! -f "$target" || ! -r "$target" ]]; then
    fail "environment file must be a readable regular file: $target"
  fi
  if [[ ! -O "$target" ]]; then
    fail "environment file must be owned by the current user: $target"
  fi
}

validate_file_boundary "$env_file"
if ! env_dir="$(CDPATH= cd -- "$(dirname -- "$env_file")" && pwd -P)"; then
  fail "environment file parent directory could not be resolved: $env_file"
fi
env_file="$env_dir/$(basename -- "$env_file")"
validate_file_boundary "$env_file"

file_mode=""
if file_mode="$(stat -f '%Lp' "$env_file" 2>/dev/null)"; then
  :
elif file_mode="$(stat -c '%a' "$env_file" 2>/dev/null)"; then
  :
else
  fail "could not inspect environment file permissions: $env_file"
fi
if [[ ! "$file_mode" =~ ^[0-7]{3,4}$ ]]; then
  fail "environment file permissions are invalid: $file_mode"
fi
file_mode_decimal=$((8#$file_mode))
if (( (file_mode_decimal & 077) != 0 )); then
  fail "environment file must not grant group or other permissions: mode=$file_mode"
fi

command -v git >/dev/null 2>&1 || fail "git is required to verify that the environment file is untracked"
git_command=(
  env
  -u GIT_DIR
  -u GIT_WORK_TREE
  -u GIT_INDEX_FILE
  -u GIT_CEILING_DIRECTORIES
  -u GIT_DISCOVERY_ACROSS_FILESYSTEM
  GIT_LITERAL_PATHSPECS=1
  git
)
env_git_root=""
if env_git_root="$("${git_command[@]}" -C "$env_dir" rev-parse --show-toplevel 2>/dev/null)"; then
  env_git_root="$(CDPATH= cd -- "$env_git_root" && pwd -P)"
  relative_env_file="${env_file#"$env_git_root"/}"
  if [[ "$relative_env_file" == "$env_file" ]]; then
    fail "could not resolve environment file inside its Git worktree: $env_file"
  fi
  if ! tracked_env_file="$("${git_command[@]}" -C "$env_git_root" ls-files -- "$relative_env_file")"; then
    fail "could not inspect whether the environment file is tracked by Git: $env_file"
  fi
  if [[ -n "$tracked_env_file" ]]; then
    fail "environment file must not be tracked by Git: $env_file"
  fi
else
  git_probe_dir="$env_dir"
  while [[ "$git_probe_dir" != "/" ]]; do
    if [[ -e "$git_probe_dir/.git" || -L "$git_probe_dir/.git" ]]; then
      fail "could not inspect the Git worktree containing the environment file: $env_file"
    fi
    git_probe_dir="${git_probe_dir%/*}"
    [[ -n "$git_probe_dir" ]] || git_probe_dir="/"
  done
fi

baton_host=""
baton_db_name=""
baton_db_username=""
baton_db_password=""
baton_db_root_password=""
baton_workspace_creation_key=""
baton_workspace_recovery_key=""
baton_identity_bootstrap_key=""
baton_identity_invitation_hmac_secret=""
baton_identity_bootstrap_invitation_ttl="PT1H"
baton_identity_member_invitation_ttl="PT24H"
baton_identity_oidc_enabled="false"
google_client_id=""
google_client_secret=""
google_redirect_uri=""
baton_go_enabled="false"
baton_go_base_url=""
baton_go_public_base_url=""
baton_go_management_token=""
baton_round_public_base_url=""
seen_baton_host=false
seen_baton_db_name=false
seen_baton_db_username=false
seen_baton_db_password=false
seen_baton_db_root_password=false
seen_baton_workspace_creation_key=false
seen_baton_workspace_recovery_key=false
seen_baton_identity_bootstrap_key=false
seen_baton_identity_invitation_hmac_secret=false
seen_baton_identity_bootstrap_invitation_ttl=false
seen_baton_identity_member_invitation_ttl=false
seen_baton_identity_oidc_enabled=false
seen_google_client_id=false
seen_google_client_secret=false
seen_google_redirect_uri=false
seen_baton_go_enabled=false
seen_baton_go_base_url=false
seen_baton_go_public_base_url=false
seen_baton_go_management_token=false
seen_baton_round_public_base_url=false
line_number=0

while IFS= read -r line || [[ -n "$line" ]]; do
  line_number=$((line_number + 1))
  if [[ "$line" == *$'\r'* ]]; then
    fail "environment file must use LF line endings: line=$line_number"
  fi
  if [[ "$line" =~ ^[[:space:]]*$ || "$line" =~ ^[[:space:]]*# ]]; then
    continue
  fi
  if [[ ! "$line" =~ ^([A-Z][A-Z0-9_]*)=([^[:space:]\"\'\$\`]+)$ ]]; then
    fail "line $line_number must be a simple literal KEY=VALUE without quotes, whitespace, or interpolation"
  fi

  key="${BASH_REMATCH[1]}"
  value="${BASH_REMATCH[2]}"
  case "$key" in
    BATON_HOST)
      [[ "$seen_baton_host" == false ]] || fail "duplicate key: $key"
      seen_baton_host=true
      baton_host="$value"
      ;;
    BATON_DB_NAME)
      [[ "$seen_baton_db_name" == false ]] || fail "duplicate key: $key"
      seen_baton_db_name=true
      baton_db_name="$value"
      ;;
    BATON_DB_USERNAME)
      [[ "$seen_baton_db_username" == false ]] || fail "duplicate key: $key"
      seen_baton_db_username=true
      baton_db_username="$value"
      ;;
    BATON_DB_PASSWORD)
      [[ "$seen_baton_db_password" == false ]] || fail "duplicate key: $key"
      seen_baton_db_password=true
      baton_db_password="$value"
      ;;
    BATON_DB_ROOT_PASSWORD)
      [[ "$seen_baton_db_root_password" == false ]] || fail "duplicate key: $key"
      seen_baton_db_root_password=true
      baton_db_root_password="$value"
      ;;
    BATON_WORKSPACE_CREATION_KEY)
      [[ "$seen_baton_workspace_creation_key" == false ]] || fail "duplicate key: $key"
      seen_baton_workspace_creation_key=true
      baton_workspace_creation_key="$value"
      ;;
    BATON_WORKSPACE_RECOVERY_KEY)
      [[ "$seen_baton_workspace_recovery_key" == false ]] || fail "duplicate key: $key"
      seen_baton_workspace_recovery_key=true
      baton_workspace_recovery_key="$value"
      ;;
    BATON_IDENTITY_BOOTSTRAP_KEY)
      [[ "$seen_baton_identity_bootstrap_key" == false ]] || fail "duplicate key: $key"
      seen_baton_identity_bootstrap_key=true
      baton_identity_bootstrap_key="$value"
      ;;
    BATON_IDENTITY_INVITATION_HMAC_SECRET)
      [[ "$seen_baton_identity_invitation_hmac_secret" == false ]] || fail "duplicate key: $key"
      seen_baton_identity_invitation_hmac_secret=true
      baton_identity_invitation_hmac_secret="$value"
      ;;
    BATON_IDENTITY_BOOTSTRAP_INVITATION_TTL)
      [[ "$seen_baton_identity_bootstrap_invitation_ttl" == false ]] || fail "duplicate key: $key"
      seen_baton_identity_bootstrap_invitation_ttl=true
      baton_identity_bootstrap_invitation_ttl="$value"
      ;;
    BATON_IDENTITY_MEMBER_INVITATION_TTL)
      [[ "$seen_baton_identity_member_invitation_ttl" == false ]] || fail "duplicate key: $key"
      seen_baton_identity_member_invitation_ttl=true
      baton_identity_member_invitation_ttl="$value"
      ;;
    BATON_IDENTITY_OIDC_ENABLED)
      [[ "$seen_baton_identity_oidc_enabled" == false ]] || fail "duplicate key: $key"
      seen_baton_identity_oidc_enabled=true
      baton_identity_oidc_enabled="$value"
      ;;
    SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID)
      [[ "$seen_google_client_id" == false ]] || fail "duplicate key: $key"
      seen_google_client_id=true
      google_client_id="$value"
      ;;
    SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET)
      [[ "$seen_google_client_secret" == false ]] || fail "duplicate key: $key"
      seen_google_client_secret=true
      google_client_secret="$value"
      ;;
    SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_REDIRECT_URI)
      [[ "$seen_google_redirect_uri" == false ]] || fail "duplicate key: $key"
      seen_google_redirect_uri=true
      google_redirect_uri="$value"
      ;;
    BATON_GO_ENABLED)
      [[ "$seen_baton_go_enabled" == false ]] || fail "duplicate key: $key"
      seen_baton_go_enabled=true
      baton_go_enabled="$value"
      ;;
    BATON_GO_BASE_URL)
      [[ "$seen_baton_go_base_url" == false ]] || fail "duplicate key: $key"
      seen_baton_go_base_url=true
      baton_go_base_url="$value"
      ;;
    BATON_GO_PUBLIC_BASE_URL)
      [[ "$seen_baton_go_public_base_url" == false ]] || fail "duplicate key: $key"
      seen_baton_go_public_base_url=true
      baton_go_public_base_url="$value"
      ;;
    BATON_GO_MANAGEMENT_TOKEN)
      [[ "$seen_baton_go_management_token" == false ]] || fail "duplicate key: $key"
      seen_baton_go_management_token=true
      baton_go_management_token="$value"
      ;;
    BATON_ROUND_PUBLIC_BASE_URL)
      [[ "$seen_baton_round_public_base_url" == false ]] || fail "duplicate key: $key"
      seen_baton_round_public_base_url=true
      baton_round_public_base_url="$value"
      ;;
    *)
      fail "unknown or unsafe production environment key: $key"
      ;;
  esac
done < "$env_file"

for required_key in \
  BATON_HOST \
  BATON_DB_NAME \
  BATON_DB_USERNAME \
  BATON_DB_PASSWORD \
  BATON_DB_ROOT_PASSWORD \
  BATON_WORKSPACE_CREATION_KEY \
  BATON_WORKSPACE_RECOVERY_KEY \
  BATON_IDENTITY_BOOTSTRAP_KEY \
  BATON_IDENTITY_INVITATION_HMAC_SECRET; do
  case "$required_key" in
    BATON_HOST) seen="$seen_baton_host" ;;
    BATON_DB_NAME) seen="$seen_baton_db_name" ;;
    BATON_DB_USERNAME) seen="$seen_baton_db_username" ;;
    BATON_DB_PASSWORD) seen="$seen_baton_db_password" ;;
    BATON_DB_ROOT_PASSWORD) seen="$seen_baton_db_root_password" ;;
    BATON_WORKSPACE_CREATION_KEY) seen="$seen_baton_workspace_creation_key" ;;
    BATON_WORKSPACE_RECOVERY_KEY) seen="$seen_baton_workspace_recovery_key" ;;
    BATON_IDENTITY_BOOTSTRAP_KEY) seen="$seen_baton_identity_bootstrap_key" ;;
    BATON_IDENTITY_INVITATION_HMAC_SECRET) seen="$seen_baton_identity_invitation_hmac_secret" ;;
  esac
  [[ "$seen" == true ]] || fail "required key is missing: $required_key"
done

validate_hostname() {
  local hostname="$1"
  local label
  local old_ifs
  local labels

  if [[ ${#hostname} -gt 253 \
    || "$hostname" != *.* \
    || "$hostname" == .* \
    || "$hostname" == *. \
    || "$hostname" == *..* \
    || "$hostname" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ \
    || ! "$hostname" =~ ^[A-Za-z0-9.-]+$ ]]; then
    return 1
  fi

  old_ifs="$IFS"
  IFS='.'
  read -r -a labels <<< "$hostname"
  IFS="$old_ifs"
  for label in "${labels[@]}"; do
    if [[ ${#label} -gt 63 \
      || ! "$label" =~ ^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?$ ]]; then
      return 1
    fi
  done
  return 0
}

validate_secret() {
  local name="$1"
  local value="$2"

  if [[ ${#value} -lt 32 || ${#value} -gt 200 || ! "$value" =~ ^[A-Za-z0-9._~-]+$ ]]; then
    fail "$name must be 32-200 URL-safe ASCII characters"
  fi
}

validate_hostname "$baton_host" \
  || fail "BATON_HOST must be a public DNS hostname without scheme, port, path, localhost, or IP"
if [[ ${#baton_db_name} -gt 64 || ! "$baton_db_name" =~ ^[A-Za-z0-9_]+$ ]]; then
  fail "BATON_DB_NAME must be 1-64 letters, digits, or underscores"
fi
if [[ ${#baton_db_username} -gt 32 \
  || ! "$baton_db_username" =~ ^[A-Za-z0-9_]+$ \
  || "$baton_db_username" == "root" ]]; then
  fail "BATON_DB_USERNAME must be a non-root 1-32 character identifier"
fi

validate_secret BATON_DB_PASSWORD "$baton_db_password"
validate_secret BATON_DB_ROOT_PASSWORD "$baton_db_root_password"
validate_secret BATON_WORKSPACE_CREATION_KEY "$baton_workspace_creation_key"
validate_secret BATON_WORKSPACE_RECOVERY_KEY "$baton_workspace_recovery_key"
validate_secret BATON_IDENTITY_BOOTSTRAP_KEY "$baton_identity_bootstrap_key"
validate_secret BATON_IDENTITY_INVITATION_HMAC_SECRET "$baton_identity_invitation_hmac_secret"
if [[ "$baton_identity_bootstrap_invitation_ttl" != "PT1H" ]]; then
  fail "BATON_IDENTITY_BOOTSTRAP_INVITATION_TTL must be exactly PT1H in production"
fi
if [[ "$baton_identity_member_invitation_ttl" != "PT24H" ]]; then
  fail "BATON_IDENTITY_MEMBER_INVITATION_TTL must be exactly PT24H in production"
fi
case "$baton_identity_oidc_enabled" in
  true|false) ;;
  *) fail "BATON_IDENTITY_OIDC_ENABLED must be exactly true or false" ;;
esac
if [[ "$baton_identity_oidc_enabled" == true ]]; then
  [[ "$seen_google_client_id" == true ]] \
    || fail "Google client id is required when BATON_IDENTITY_OIDC_ENABLED=true"
  [[ "$seen_google_client_secret" == true ]] \
    || fail "Google client secret is required when BATON_IDENTITY_OIDC_ENABLED=true"
  [[ "$seen_google_redirect_uri" == true ]] \
    || fail "Google redirect URI is required when BATON_IDENTITY_OIDC_ENABLED=true"
fi
if [[ "$seen_google_client_id" == true \
  && ( -z "$google_client_id" \
    || ${#google_client_id} -gt 512 \
    || ! "$google_client_id" =~ ^[A-Za-z0-9._~-]+$ ) ]]; then
  fail "Google client id must be 1-512 URL-safe ASCII characters"
fi
if [[ "$seen_google_client_secret" == true ]]; then
  validate_secret SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET \
    "$google_client_secret"
fi
if [[ "$seen_google_redirect_uri" == true \
  && "$google_redirect_uri" != '{baseUrl}/api/v1/auth/oidc/callback/{registrationId}' ]]; then
  fail "Google redirect URI must use the fixed BATON OIDC callback template"
fi

case "$baton_go_enabled" in
  true|false) ;;
  *) fail "BATON_GO_ENABLED must be exactly true or false" ;;
esac

validate_https_origin() {
  local name="$1"
  local value="$2"
  local authority="${value#https://}"

  if [[ "$value" != https://* \
    || -z "$authority" \
    || "$authority" == */*/* \
    || "$authority" == *'@'* \
    || "$authority" == *'?'* \
    || "$authority" == *'#'* ]]; then
    fail "$name must be an HTTPS origin without userinfo, path, query, or fragment"
  fi
  authority="${authority%/}"
  if [[ ! "$authority" =~ ^[A-Za-z0-9.-]+(:[0-9]{1,5})?$ ]]; then
    fail "$name must be an HTTPS origin without userinfo, path, query, or fragment"
  fi
}

if [[ "$baton_go_enabled" == true ]]; then
  [[ "$seen_baton_go_base_url" == true ]] \
    || fail "BATON_GO_BASE_URL is required when BATON_GO_ENABLED=true"
  [[ "$seen_baton_go_public_base_url" == true ]] \
    || fail "BATON_GO_PUBLIC_BASE_URL is required when BATON_GO_ENABLED=true"
  [[ "$seen_baton_go_management_token" == true ]] \
    || fail "BATON_GO_MANAGEMENT_TOKEN is required when BATON_GO_ENABLED=true"
  [[ "$seen_baton_round_public_base_url" == true ]] \
    || fail "BATON_ROUND_PUBLIC_BASE_URL is required when BATON_GO_ENABLED=true"
fi
if [[ "$seen_baton_go_base_url" == true ]]; then
  validate_https_origin BATON_GO_BASE_URL "$baton_go_base_url"
fi
if [[ "$seen_baton_go_public_base_url" == true ]]; then
  validate_https_origin BATON_GO_PUBLIC_BASE_URL "$baton_go_public_base_url"
fi
if [[ "$seen_baton_round_public_base_url" == true ]]; then
  validate_https_origin BATON_ROUND_PUBLIC_BASE_URL "$baton_round_public_base_url"
fi
if [[ "$seen_baton_go_management_token" == true ]]; then
  validate_secret BATON_GO_MANAGEMENT_TOKEN "$baton_go_management_token"
fi

secrets=(
  "$baton_db_password"
  "$baton_db_root_password"
  "$baton_workspace_creation_key"
  "$baton_workspace_recovery_key"
  "$baton_identity_bootstrap_key"
  "$baton_identity_invitation_hmac_secret"
)
if [[ "$seen_google_client_secret" == true ]]; then
  secrets+=("$google_client_secret")
fi
if [[ "$seen_baton_go_management_token" == true ]]; then
  secrets+=("$baton_go_management_token")
fi
for ((left = 0; left < ${#secrets[@]}; left += 1)); do
  for ((right = left + 1; right < ${#secrets[@]}; right += 1)); do
    if [[ "${secrets[$left]}" == "${secrets[$right]}" ]]; then
      fail "production secrets must all be independently generated"
    fi
  done
done

printf '%s\n' "$env_file"
