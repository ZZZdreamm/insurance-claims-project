#!/bin/sh
# Creates Kibana data views for the two indices search-service maintains, so Discover/Lens work immediately.
set -e
K=${KIBANA_URL:-http://kibana:5601}
create() {
  id=$1; ts=$2
  if [ -n "$ts" ]; then extra=",\"timeFieldName\":\"$ts\""; else extra=""; fi
  body="{\"data_view\":{\"id\":\"$id\",\"title\":\"$id\",\"name\":\"$id\"$extra}}"
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$K/api/data_views/data_view" -H 'kbn-xsrf: true' -H 'Content-Type: application/json' -d "$body")
  echo "data view $id -> HTTP $code (409 = already exists)"
}
create claims ""
create claim-events "@timestamp"
curl -s -X POST "$K/api/data_views/default" -H 'kbn-xsrf: true' -H 'Content-Type: application/json' -d '{"data_view_id":"claim-events","force":true}' > /dev/null
echo "Kibana ready: http://localhost:5601/app/discover"
