#!/bin/bash

# 유틸리티 함수
log() {
    local timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    echo "[$timestamp] $1"
}

SUB_HOST="https://dg0100.resilience.20.41.83.72.nip.io/api/events/point"

log "## check SubscriptionValidation"
curl -X POST $SUB_HOST \
  -H "Content-Type: application/json" \
  -H "aeg-event-type: SubscriptionValidation" \
  -d '[{
    "id": "2d1781af-3a4c-4d7c-bd0c-e34b19da4e66",
    "topic": "/subscriptions/1234-5678/resourceGroups/test",
    "subject": "validationRequest",
    "eventType": "SubscriptionValidation",
    "data": {
      "validationCode": "512d38b6-c7b8-40c8-89fe-f46f9e9622b6"
    },
    "eventTime": "2024-12-08T10:00:00.000Z",
    "metadataVersion": "1",
    "dataVersion": "1"
  }]'
