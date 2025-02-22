#!/bin/bash

RESOURCE_GROUP="ictcoe-edu" #az group list -o table

# ===========================================
# 신뢰성 패턴 실습환경 정리 스크립트
# ===========================================

print_usage() {
    cat << EOF
사용법: $0 <userid>
설명: 신뢰성 패턴 실습 리소스를 정리합니다.
예제: $0 dg0100
EOF
}

log() {
    local timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    echo "[$timestamp] $1"
}

if [ $# -ne 1 ]; then
    print_usage
    exit 1
fi

if [[ ! $1 =~ ^[a-z0-9]+$ ]]; then
    echo "Error: userid는 영문 소문자와 숫자만 사용할 수 있습니다."
    exit 1
fi

# 환경 변수 설정
NAME="${1}-resilience"
NAMESPACE="${NAME}-ns"
GATEWAY_TOPIC="$NAME-gateway-topic"

# 리소스 삭제 전 확인
confirm() {
    read -p "모든 리소스를 삭제하시겠습니까? (y/N) " response
    case "$response" in
        [yY][eE][sS]|[yY])
            return 0
            ;;
        *)
            echo "작업을 취소합니다."
            exit 1
            ;;
    esac
}

# Event Grid 리소스 삭제
cleanup_event_grid() {
    log "Event Grid 리소스 삭제 중..."

    # Gateway Topic 존재 여부 확인 및 삭제
    local gateway_topic_exists=$(az eventgrid topic show \
        --name $GATEWAY_TOPIC \
        --resource-group $RESOURCE_GROUP \
        --query id -o tsv 2>/dev/null)

    if [ ! -z "$gateway_topic_exists" ]; then
        # Subscription 삭제
        az eventgrid event-subscription delete \
            --name "${NAME}-sub" \
            --source-resource-id $gateway_topic_exists \
            2>/dev/null || true

        # Topic 삭제
        az eventgrid topic delete \
            --name $GATEWAY_TOPIC \
            --resource-group $RESOURCE_GROUP

        log "Gateway Event Grid Topic 삭제 완료"
    else
        log "Gateway Event Grid Topic이 존재하지 않습니다"
    fi

}

cleanup_storage() {
   log "Storage Account 리소스 정리 중..."

   # Dead Letter Container 삭제
   az storage container delete \
       --name $DEAD_LETTER \
       --account-name $STORAGE_ACCOUNT \
       2>/dev/null || true

   # Storage Account 삭제
   az storage account delete \
       --name $STORAGE_ACCOUNT \
       --resource-group $RESOURCE_GROUP \
       --yes \
       2>/dev/null || true

   log "Storage Account 리소스 정리 완료"
}

# Kubernetes 리소스 삭제
cleanup_kubernetes() {
    log "Kubernetes 리소스 삭제 중..."

    # StatefulSet 삭제
    kubectl delete statefulset -n $NAMESPACE --all 2>/dev/null || true

    # Deployment 삭제
    kubectl delete deployment -n $NAMESPACE --all 2>/dev/null || true

    # Service 삭제
    kubectl delete service -n $NAMESPACE --all 2>/dev/null || true

    # ConfigMap 삭제
    kubectl delete configmap -n $NAMESPACE --all 2>/dev/null || true

    # Secret 삭제
    kubectl delete secret -n $NAMESPACE --all 2>/dev/null || true

    # PVC 삭제
    kubectl delete pvc -n $NAMESPACE --all 2>/dev/null || true

    # Namespace 삭제
    kubectl delete namespace $NAMESPACE 2>/dev/null || true

    log "Kubernetes 리소스 삭제 완료"
}

main() {
    log "리소스 정리를 시작합니다..."

    # 사전 체크
    confirm

    # Event Grid 리소스 삭제
    cleanup_event_grid

    # Storage Account 리소스 정리
    cleanup_storage

    # Kubernetes 리소스 삭제
    cleanup_kubernetes

    # 남은 리소스 확인
    log "=== 남은 리소스 확인 ==="
    az resource list --resource-group $RESOURCE_GROUP --output table | grep $NAME || echo "남은 리소스 없음"

    kubectl get all -n $NAMESPACE 2>/dev/null || echo "남은 Kubernetes 리소스 없음"

    log "모든 리소스가 정리되었습니다."
}

main
