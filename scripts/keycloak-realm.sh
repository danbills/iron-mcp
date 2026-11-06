#!/usr/bin/env bash
# Creates (or updates) the `iron-mcp` Keycloak realm from
# modules/auth/keycloak/iron-mcp-realm.json, through the admin REST API.
#
#   KC_URL=https://danarch.tail09c16c.ts.net:10000 \
#   KC_ADMIN_USER=admin KC_ADMIN_PASSWORD=... \
#   MCP_RESOURCE=https://reports.example.com/mcp \
#   scripts/keycloak-realm.sh [--machine-jwks https://client.example/jwks.json] [--test-user NAME]
#
# Nothing secret is stored in the repo. Keycloak generates client secrets; the
# script prints them once, with the env lines KeycloakIntegrationSuite reads.
# --test-user asks for a password on the terminal.
set -euo pipefail

: "${KC_URL:?set KC_URL (e.g. https://danarch.tail09c16c.ts.net:10000)}"
: "${KC_ADMIN_USER:?set KC_ADMIN_USER}"
: "${KC_ADMIN_PASSWORD:?set KC_ADMIN_PASSWORD}"
: "${MCP_RESOURCE:?set MCP_RESOURCE to the MCP server public URL, e.g. https://reports.example.com/mcp}"

machine_jwks=""
test_user=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --machine-jwks) machine_jwks="$2"; shift 2 ;;
    --test-user)    test_user="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

here="$(cd "$(dirname "$0")/.." && pwd)"
spec="$here/modules/auth/keycloak/iron-mcp-realm.json"
KC_URL="${KC_URL%/}"
realm="$(jq -r '.realm.realm' "$spec")"

use_jwks_url=false
[[ -n "$machine_jwks" ]] && use_jwks_url=true
doc="$(sed -e "s#__MCP_RESOURCE__#${MCP_RESOURCE}#g" \
           -e "s#__MACHINE_USE_JWKS_URL__#${use_jwks_url}#g" \
           -e "s#__MACHINE_JWKS_URL__#${machine_jwks}#g" "$spec")"

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

# 1. The realm. Creating it bare first lets Keycloak add its built-in scopes
#    (basic, which carries `sub`; acr; ...), which the clients use.
if [[ "$(status "/$realm")" == 404 ]]; then
  api POST "" "$(jq -c '{realm: .realm.realm, enabled: true}' <<<"$doc")" >/dev/null
  echo "created realm $realm"
fi
api PUT "/$realm" "$(jq -c '.realm' <<<"$doc")" >/dev/null

realm_id="$(api GET "/$realm" | jq -r .id)"
jq -c '.keyProviders[]' <<<"$doc" | while read -r provider; do
  name="$(jq -r .name <<<"$provider")"
  if ! api GET "/$realm/components?type=org.keycloak.keys.KeyProvider" | jq -e --arg n "$name" 'any(.[]; .name == $n)' >/dev/null; then
    api POST "/$realm/components" "$(jq -c --arg p "$realm_id" '. + {parentId: $p}' <<<"$provider")" >/dev/null
    echo "added key provider $name"
  fi
done

# 2. Client scopes, each with its audience mapper.
jq -c '.clientScopes[]' <<<"$doc" | while read -r scope; do
  name="$(jq -r .name <<<"$scope")"
  id="$(api GET "/$realm/client-scopes" | jq -r --arg n "$name" '.[] | select(.name == $n) | .id')"
  if [[ -z "$id" ]]; then
    api POST "/$realm/client-scopes" "$scope" >/dev/null
    echo "created scope $name"
  else
    api PUT "/$realm/client-scopes/$id" "$(jq -c --arg id "$id" '. + {id: $id} | del(.protocolMappers)' <<<"$scope")" >/dev/null
    for mapper in $(api GET "/$realm/client-scopes/$id/protocol-mappers/models" | jq -r '.[] | select(.name == "mcp-audience") | .id'); do
      api DELETE "/$realm/client-scopes/$id/protocol-mappers/models/$mapper" >/dev/null
    done
    jq -c '.protocolMappers[]' <<<"$scope" | while read -r m; do
      api POST "/$realm/client-scopes/$id/protocol-mappers/models" "$m" >/dev/null
    done
    echo "updated scope $name"
  fi
done

# 3. Clients, and their default and optional scopes.
scope_id() { api GET "/$realm/client-scopes" | jq -r --arg n "$1" '.[] | select(.name == $n) | .id'; }
jq -c '.clients[]' <<<"$doc" | while read -r client; do
  cid="$(jq -r .clientId <<<"$client")"
  id="$(api GET "/$realm/clients?clientId=$cid" | jq -r '.[0].id // empty')"
  body="$(jq -c 'del(.defaultClientScopes, .optionalClientScopes)' <<<"$client")"
  if [[ -z "$id" ]]; then
    api POST "/$realm/clients" "$body" >/dev/null
    id="$(api GET "/$realm/clients?clientId=$cid" | jq -r '.[0].id')"
    echo "created client $cid"
  else
    api PUT "/$realm/clients/$id" "$(jq -c --arg id "$id" '. + {id: $id}' <<<"$body")" >/dev/null
    echo "updated client $cid"
  fi
  # Exactly the listed scopes: drop Keycloak's defaults (profile, email, roles, ...).
  for kind in default optional; do
    for existing in $(api GET "/$realm/clients/$id/$kind-client-scopes" | jq -r '.[].id'); do
      api DELETE "/$realm/clients/$id/$kind-client-scopes/$existing" >/dev/null
    done
  done
  for s in $(jq -r '.defaultClientScopes[]' <<<"$client"); do api PUT "/$realm/clients/$id/default-client-scopes/$(scope_id "$s")" >/dev/null; done
  for s in $(jq -r '.optionalClientScopes[]' <<<"$client"); do api PUT "/$realm/clients/$id/optional-client-scopes/$(scope_id "$s")" >/dev/null; done
done

# 4. A user to sign in as, if asked for.
if [[ -n "$test_user" ]]; then
  read -r -s -p "password for $test_user (14+ characters): " pw; echo
  if [[ -z "$(api GET "/$realm/users?username=$test_user&exact=true" | jq -r '.[0].id // empty')" ]]; then
    api POST "/$realm/users" "$(jq -nc --arg u "$test_user" '{username: $u, enabled: true, emailVerified: true}')" >/dev/null
  fi
  uid="$(api GET "/$realm/users?username=$test_user&exact=true" | jq -r '.[0].id')"
  api PUT "/$realm/users/$uid/reset-password" "$(jq -nc --arg p "$pw" '{type: "password", value: $p, temporary: false}')" >/dev/null
  echo "user $test_user is ready"
fi

secret_of() { api GET "/$realm/clients/$(api GET "/$realm/clients?clientId=$1" | jq -r '.[0].id')/client-secret" | jq -r .value; }

cat <<OUT

Realm:   $KC_URL/realms/$realm
Issuer:  $(curl -fsS "$KC_URL/realms/$realm/.well-known/openid-configuration" | jq -r .issuer)

For KeycloakIntegrationSuite (keep these out of the repo):
  export IRON_MCP_KC_REALM=$KC_URL/realms/$realm
  export IRON_MCP_KC_CLIENT=iron-mcp-ci
  export IRON_MCP_KC_SECRET=$(secret_of iron-mcp-ci)
  export IRON_MCP_KC_RESOURCE=$MCP_RESOURCE

For Introspection.Config (the resource server's own credentials):
  iron-mcp-introspector / $(secret_of iron-mcp-introspector)
OUT
