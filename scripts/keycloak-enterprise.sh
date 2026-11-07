#!/usr/bin/env bash
# Sets up Enterprise-Managed Authorization (ext-auth) on Keycloak, on top of
# the `iron-mcp` realm from scripts/keycloak-realm.sh:
#
#   iron-mcp-corp   a second realm playing the company's SSO: client
#                   corp-desktop, group engineering, user ada (in it) and
#                   user grace (not in it)
#   iron-mcp        trusts ID-JAGs from IDJAG_ISSUER (an IdJagIssuer, which
#                   checks corp's ID tokens and the admins' policy) through
#                   Keycloak's identity-assertion-jwt feature (experimental,
#                   26.7: start Keycloak with --features=identity-assertion-jwt);
#                   client iron-mcp-enterprise redeems them; ada and grace are
#                   linked to their corp identities
#
#   KC_URL=... KC_ADMIN_USER=... KC_ADMIN_PASSWORD=... \
#   IDJAG_ISSUER=https://idp.iron-mcp.test IDJAG_PUBLIC_KEY=idjag-pub.pem \
#   [MACHINE_JWKS=machine-jwks.json] scripts/keycloak-enterprise.sh
#
# The trust is pinned to ES256, the access token lives no longer than the
# ID-JAG, and 15 s of clock skew are allowed (Keycloak allows none by default,
# so a client a second fast is refused with "Token was issued in the future").
#
# MACHINE_JWKS registers the public keys of iron-mcp-machine (private_key_jwt)
# inline, for machines that publish no JWKS URL.
#
# corp-desktop has the password grant so tests can sign in without a browser.
# That is for this test realm only: a real SSO client uses the code flow.
# Passwords and secrets are generated here and printed once, as env lines;
# ROTATE=1 sets new passwords for the test users.
set -euo pipefail

: "${KC_URL:?set KC_URL}"
: "${KC_ADMIN_USER:?set KC_ADMIN_USER}"
: "${KC_ADMIN_PASSWORD:?set KC_ADMIN_PASSWORD}"
: "${IDJAG_ISSUER:?set IDJAG_ISSUER, the issuer of the ID-JAGs}"
: "${IDJAG_PUBLIC_KEY:?set IDJAG_PUBLIC_KEY to the PEM public key the ID-JAGs are signed with}"
KC_URL="${KC_URL%/}"
corp=iron-mcp-corp
realm=iron-mcp
idp=corp-idjag

token="$(curl -fsS "$KC_URL/realms/master/protocol/openid-connect/token" \
  --data-urlencode grant_type=password --data-urlencode client_id=admin-cli \
  --data-urlencode "username=$KC_ADMIN_USER" --data-urlencode "password=$KC_ADMIN_PASSWORD" | jq -r .access_token)"

api() { # method path [json]
  local method="$1" path="$2" body="${3:-}"
  if [[ -n "$body" ]]; then
    curl -fsS -X "$method" "$KC_URL/admin/realms$path" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' --data "$body"
  else
    curl -fsS -X "$method" "$KC_URL/admin/realms$path" -H "Authorization: Bearer $token"
  fi
}
status() { curl -s -o /dev/null -w '%{http_code}' "$KC_URL/admin/realms$1" -H "Authorization: Bearer $token"; }
client_uuid() { api GET "/$1/clients?clientId=$2" | jq -r '.[0].id // empty'; }
user_uuid() { api GET "/$1/users?username=$2&exact=true" | jq -r '.[0].id // empty'; }
upsert_client() { # realm json
  local id; id="$(client_uuid "$1" "$(jq -r .clientId <<<"$2")")"
  if [[ -z "$id" ]]; then api POST "/$1/clients" "$2" >/dev/null; else api PUT "/$1/clients/$id" "$(jq -c --arg id "$id" '. + {id: $id}' <<<"$2")" >/dev/null; fi
}
password() { head -c 24 /dev/urandom | base64 | tr -d '+/=' ; }

# 1. The company's SSO realm.
if [[ "$(status "/$corp")" == 404 ]]; then
  api POST "" "$(jq -nc --arg r "$corp" '{realm: $r, enabled: true}')" >/dev/null
  echo "created realm $corp" >&2
fi
api PUT "/$corp" '{"accessTokenLifespan": 300, "ssoSessionIdleTimeout": 1800, "bruteForceProtected": true, "passwordPolicy": "length(14)"}' >/dev/null

upsert_client "$corp" "$(jq -nc '{
  clientId: "corp-desktop", enabled: true, publicClient: false, protocol: "openid-connect",
  standardFlowEnabled: true, directAccessGrantsEnabled: true, serviceAccountsEnabled: false,
  redirectUris: ["http://127.0.0.1/*"],
  attributes: {"pkce.code.challenge.method": "S256", "id.token.signed.response.alg": "ES256"}
}')"
desktop="$(client_uuid "$corp" corp-desktop)"
if ! api GET "/$corp/clients/$desktop/protocol-mappers/models" | jq -e 'any(.[]; .name == "groups")' >/dev/null; then
  api POST "/$corp/clients/$desktop/protocol-mappers/models" '{
    "name": "groups", "protocol": "openid-connect", "protocolMapper": "oidc-group-membership-mapper",
    "config": {"claim.name": "groups", "full.path": "false", "id.token.claim": "true", "access.token.claim": "false", "userinfo.token.claim": "true"}
  }' >/dev/null
fi
if ! api GET "/$corp/keys" | jq -e 'any(.keys[]; .algorithm == "ES256")' >/dev/null; then
  api POST "/$corp/components" "$(jq -nc --arg p "$(api GET "/$corp" | jq -r .id)" '{
    name: "ecdsa-p256", providerId: "ecdsa-generated", providerType: "org.keycloak.keys.KeyProvider", parentId: $p,
    config: {priority: ["200"], ecdsaEllipticCurveKey: ["P-256"]}
  }')" >/dev/null
fi

if [[ -z "$(api GET "/$corp/groups?search=engineering&exact=true" | jq -r '.[0].id // empty')" ]]; then
  api POST "/$corp/groups" '{"name": "engineering"}' >/dev/null
fi
eng="$(api GET "/$corp/groups?search=engineering&exact=true" | jq -r '.[0].id')"

declare -A passwords
# Passwords are set when a user is created, or for all with ROTATE=1, and
# printed only then: re-running the script leaves working settings alone.
for u in ada grace; do
  fresh=false
  if [[ -z "$(user_uuid "$corp" "$u")" ]]; then
    api POST "/$corp/users" "$(jq -nc --arg u "$u" '{username: $u, email: ($u + "@corp.iron-mcp.test"), firstName: $u, lastName: "Test", enabled: true, emailVerified: true}')" >/dev/null
    fresh=true
  fi
  if [[ "$fresh" == true || "${ROTATE:-}" == 1 ]]; then
    pw="$(password)"; passwords[$u]="$pw"
    api PUT "/$corp/users/$(user_uuid "$corp" "$u")/reset-password" "$(jq -nc --arg p "$pw" '{type: "password", value: $p, temporary: false}')" >/dev/null
  fi
done
api PUT "/$corp/users/$(user_uuid "$corp" ada)/groups/$eng" >/dev/null

# 2. iron-mcp trusts ID-JAGs from the issuer, by its public key.
key="$(grep -v -- '-----' "$IDJAG_PUBLIC_KEY" | tr -d '\n')"
provider="$(jq -nc --arg a "$idp" --arg iss "${IDJAG_ISSUER%/}" --arg key "$key" '{
  alias: $a, providerId: "oidc", enabled: true, displayName: "Corp ID-JAG issuer",
  hideOnLogin: true, linkOnly: true, trustEmail: false, storeToken: false,
  config: {
    issuer: $iss,
    authorizationUrl: ($iss + "/authorize"), tokenUrl: ($iss + "/token"),
    clientId: "iron-mcp", clientSecret: "unused", clientAuthMethod: "client_secret_post",
    validateSignature: "true", useJwksUrl: "false",
    publicKeySignatureVerifier: $key, publicKeySignatureVerifierKeyId: "idjag-1",
    jwtAuthorizationGrantEnabled: "true",
    jwtAuthorizationGrantAssertionReuseAllowed: "false",
    jwtAuthorizationGrantMaxAllowedAssertionExpiration: "300",
    jwtAuthorizationGrantAssertionSignatureAlg: "ES256",
    jwtAuthorizationGrantLimitAccessTokenExp: "true",
    jwtAuthorizationGrantAllowedClockSkew: "15",
    syncMode: "IMPORT"
  }
}')"
if [[ "$(status "/$realm/identity-provider/instances/$idp")" == 404 ]]; then
  api POST "/$realm/identity-provider/instances" "$provider" >/dev/null
else
  api PUT "/$realm/identity-provider/instances/$idp" "$provider" >/dev/null
fi

upsert_client "$realm" "$(jq -nc --arg idp "$idp" '{
  clientId: "iron-mcp-enterprise", enabled: true, publicClient: false, protocol: "openid-connect",
  standardFlowEnabled: false, directAccessGrantsEnabled: false, serviceAccountsEnabled: false, consentRequired: false,
  attributes: {
    "oauth2.jwt.authorization.grant.enabled": "true",
    "oauth2.jwt.authorization.grant.idp": $idp,
    "access.token.signed.response.alg": "ES256"
  }
}')"
ent="$(client_uuid "$realm" iron-mcp-enterprise)"
for kind in default optional; do
  for existing in $(api GET "/$realm/clients/$ent/$kind-client-scopes" | jq -r '.[].id'); do
    api DELETE "/$realm/clients/$ent/$kind-client-scopes/$existing" >/dev/null
  done
done
scope_id() { api GET "/$realm/client-scopes" | jq -r --arg n "$1" '.[] | select(.name == $n) | .id'; }
for s in basic mcp:read; do api PUT "/$realm/clients/$ent/default-client-scopes/$(scope_id "$s")" >/dev/null; done
for s in mcp:export mcp:admin; do api PUT "/$realm/clients/$ent/optional-client-scopes/$(scope_id "$s")" >/dev/null; done

# Each corp user has a local account, linked to the corp `sub` the ID-JAG names.
for u in ada grace; do
  [[ -z "$(user_uuid "$realm" "$u")" ]] && api POST "/$realm/users" "$(jq -nc --arg u "$u" '{username: $u, enabled: true, emailVerified: true}')" >/dev/null
  local_id="$(user_uuid "$realm" "$u")"
  corp_sub="$(user_uuid "$corp" "$u")"
  api DELETE "/$realm/users/$local_id/federated-identity/$idp" >/dev/null 2>&1 || true
  api POST "/$realm/users/$local_id/federated-identity/$idp" "$(jq -nc --arg s "$corp_sub" --arg u "$u" '{identityProvider: "corp-idjag", userId: $s, userName: $u}')" >/dev/null
done

# 3. iron-mcp-machine authenticates with private_key_jwt against inline keys.
#    Keycloak 26.7 with resource-indicators fails (HTTP 500, a NullPointerException)
#    on a `resource` in a grant that issues no refresh token, so this client gets
#    one; it cannot use it without its signed assertion.
if [[ -n "${MACHINE_JWKS:-}" ]]; then
  mid="$(client_uuid "$realm" iron-mcp-machine)"
  api PUT "/$realm/clients/$mid" "$(api GET "/$realm/clients/$mid" | jq -c --arg jwks "$(jq -c . "$MACHINE_JWKS")" '
    .clientAuthenticatorType = "client-jwt"
    | .attributes += {"use.jwks.url": "false", "use.jwks.string": "true", "jwks.string": $jwks, "token.endpoint.auth.signing.alg": "ES256",
                     "client_credentials.use_refresh_token": "true"}')" >/dev/null
  echo "iron-mcp-machine uses private_key_jwt with the keys in $MACHINE_JWKS" >&2
fi

secret_of() { api GET "/$1/clients/$(client_uuid "$1" "$2")/client-secret" | jq -r .value; }
cat <<OUT
export IRON_MCP_KC_CORP_REALM=$KC_URL/realms/$corp
export IRON_MCP_KC_CORP_CLIENT=corp-desktop
export IRON_MCP_KC_CORP_SECRET=$(secret_of "$corp" corp-desktop)
$(for u in ada grace; do [[ -n "${passwords[$u]:-}" ]] && echo "export IRON_MCP_KC_CORP_${u^^}_PASSWORD=${passwords[$u]}"; done)
export IRON_MCP_KC_ENTERPRISE_CLIENT=iron-mcp-enterprise
export IRON_MCP_KC_ENTERPRISE_SECRET=$(secret_of "$realm" iron-mcp-enterprise)
export IRON_MCP_KC_IDJAG_ISSUER=${IDJAG_ISSUER%/}
OUT
