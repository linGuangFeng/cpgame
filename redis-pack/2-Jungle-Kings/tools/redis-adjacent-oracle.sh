#!/bin/sh
set -eu

redis_cli=${REDIS_CLI:-/private/tmp/redis-7.2.7/src/redis-cli}
redis_port=${REDIS_PORT:-29612}
api_base=${API_BASE:-http://127.0.0.1:29602}
namespace=${REDIS_NAMESPACE:-cpgame:runtime:2:jungle-kings}
request_id=${1:-oracle-gid2-final}
mode=${2:-claim-and-replay}

redis() { "$redis_cli" -p "$redis_port" --raw "$@"; }
value_or_zero() { value=$(redis "$@"); printf '%s' "${value:-0}"; }
snapshot() {
  phase=$1
  ready=$(value_or_zero LLEN "$namespace:ready")
  open_claims=$(value_or_zero HLEN "$namespace:claims")
  response_exists=$(value_or_zero HEXISTS "$namespace:responses" "$request_id")
  claims=$(value_or_zero HGET "$namespace:metrics" claims)
  commits=$(value_or_zero HGET "$namespace:metrics" commits)
  history_writes=$(value_or_zero HGET "$namespace:metrics" historyWrites)
  history=$(value_or_zero LLEN "$namespace:history:ids")
  balance=$(value_or_zero GET "$namespace:balance-cents")
  jq -nc --arg phase "$phase" --arg requestId "$request_id" \
    --argjson ready "$ready" --argjson openClaims "$open_claims" \
    --argjson responseExists "$response_exists" --argjson claims "$claims" \
    --argjson commits "$commits" --argjson historyWrites "$history_writes" \
    --argjson history "$history" --argjson balanceCents "$balance" \
    '{kind:"REDIS_SNAPSHOT",phase:$phase,requestId:$requestId,ready:$ready,openClaims:$openClaims,responseExists:$responseExists,metrics:{claims:$claims,commits:$commits,historyWrites:$historyWrites},historyCount:$history,balanceCents:$balanceCents}'
}
spin() {
  curl --fail --silent --show-error --request POST \
    --header 'content-type: application/x-www-form-urlencoded' \
    --data-urlencode 'gid=2' --data-urlencode 't=local-gid2-session' \
    --data-urlencode 'bl=1' --data-urlencode 'bs=0.5' \
    --data-urlencode "request_id=$request_id" "$api_base/cp/api/v1/jungle-kings/spin-v2"
}
response_event() {
  phase=$1
  body=$2
  sha=$(printf '%s' "$body" | shasum -a 256 | awk '{print $1}')
  jq -nc --arg phase "$phase" --arg requestId "$request_id" --arg responseSha256 "$sha" --argjson body "$body" \
    '{kind:"HTTP_RESPONSE",phase:$phase,requestId:$requestId,responseSha256:$responseSha256,body:$body}'
}

snapshot "${mode}:before"
first=$(spin)
response_event "${mode}:response-1" "$first"
snapshot "${mode}:after-response-1"
if [ "$mode" = "claim-and-replay" ]; then
  second=$(spin)
  response_event "${mode}:response-2" "$second"
  snapshot "${mode}:after-response-2"
fi
