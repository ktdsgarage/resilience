#!/bin/bash

SHARED_PREFIX="dg0100"  #실습 시 tiu-dgga로 변경 

# ===========================================
# 신뢰성 패턴 실습환경 구성 스크립트
# ===========================================

# 사용법 출력
print_usage() {
	cat << EOF
사용법:
	$0 <userid>

설명:
	신뢰성 패턴 실습을 위한 Azure 리소스를 생성합니다.
	리소스 이름이 중복되지 않도록 userid를 prefix로 사용합니다.

예제:
	$0 dg0100  
EOF
}

# 유틸리티 함수
log() {
	local timestamp=$(date '+%Y-%m-%d %H:%M:%S')
	echo "[$timestamp] $1" | tee -a $LOG_FILE
}

check_error() {
	local status=$?
	if [ $status -ne 0 ]; then
		log "Error: $1 (Exit Code: $status)"
		exit $status
	fi
}

# Azure CLI 로그인 체크
check_azure_cli() {
	log "Azure CLI 로그인 상태 확인 중..."
	if ! az account show &> /dev/null; then
		log "Azure CLI 로그인이 필요합니다."
		az login --use-device-code
		check_error "Azure 로그인 실패"
	fi
}

# 환경 변수 설정
setup_environment() {
	USERID=$1
	NAME="${USERID}-resilience"
	NAMESPACE="${NAME}-ns"
	RESOURCE_GROUP="${SHARED_PREFIX}-rg"
	LOCATION="koreacentral"
	AKS_NAME="${USERID}-aks"
	ACR_NAME="${USERID}cr"

	# MongoDB 설정
	MONGODB_HOST="mongodb-async"
	MONGODB_PORT="27017"
	MONGODB_DATABASE="membershipdb"
	MONGODB_USER="root"
	MONGODB_PASSWORD="Passw0rd"

	# PostgreSQL 설정
	POSTGRES_HOST="postgres-point"
	POSTGRES_PORT="5432"
	POSTGRES_DB="membershipdb"
	POSTGRES_USER="postgres"
	POSTGRES_PASSWORD="postgres"

	# Secret 이름
	MONGO_SECRET_NAME="mongodb-async"
	POSTGRES_SECRET_NAME="postgres-point"

	# Event Grid 설정
	GATEWAY_TOPIC="$NAME-gateway-topic"

	# Gateway Event Grid 환경변수
	EVENTGRID_ENDPOINT=""
	EVENTGRID_KEY=""
	EVENTGRID_EVENT_TYPES="CircuitBreakerOpened CircuitBreakerError RetryExhausted"

	# Event Grid
	STORAGE_ACCOUNT="${USERID}storage"
	DEAD_LETTER="${USERID}deadletter"

	# Event Grid IPs
	PROXY_IP="20.41.83.72"
	ASYNC_PUBIP="4.230.153.85"
	SUB_ENDPOINT="https://${USERID}.resilience.${PROXY_IP}.nip.io/api/events/point"

	LOG_FILE="deployment_${NAME}.log"

	log "환경 변수 설정 완료"
}

# k8s object 삭제
clear_resources() {
	# 기존 리소스 삭제
	kubectl delete deploy --all -n $NAMESPACE 2>/dev/null || true
	kubectl delete sts --all -n $NAMESPACE 2>/dev/null || true
	kubectl delete pvc --all -n $NAMESPACE 2>/dev/null || true
	kubectl delete cm --all -n $NAMESPACE 2>/dev/null || true
	kubectl delete secret --all -n $NAMESPACE 2>/dev/null || true

  kubectl delete svc point-mart point-convenience point-online --all -n $NAMESPACE 2>/dev/null || true
}

# 공통 리소스 설정
setup_common_resources() {
	log "공통 리소스(EventGrip Topic, StorageAccount, Deadletter) 설정 중..."

	# 네임스페이스 생성
	kubectl create namespace $NAMESPACE 2>/dev/null || true

	# Gateway Event Grid Topic 체크 및 생성
	local topic_exists=$(az eventgrid topic show \
		--name $GATEWAY_TOPIC \
		--resource-group $RESOURCE_GROUP \
		--query "provisioningState" -o tsv 2>/dev/null)

	if [ "$topic_exists" != "Succeeded" ]; then
		log "Gateway Event Grid Topic 생성 중..."
		az eventgrid topic create \
			--name $GATEWAY_TOPIC \
			--resource-group $RESOURCE_GROUP \
			--location $LOCATION \
			--output none
		check_error "Gateway Event Grid Topic 생성 실패"
	else
		log "Gateway Event Grid Topic이 이미 존재합니다"
	fi

	# Gateway Event Grid 환경변수 설정
	EVENTGRID_ENDPOINT=$(az eventgrid topic show --name $GATEWAY_TOPIC -g $RESOURCE_GROUP --query "endpoint" -o tsv)
	EVENTGRID_KEY=$(az eventgrid topic key list --name $GATEWAY_TOPIC -g $RESOURCE_GROUP --query "key1" -o tsv)

	# Storage Account가 없으면 생성
	STORAGE_EXISTS=$(az storage account show \
		--name $STORAGE_ACCOUNT \
		--resource-group $RESOURCE_GROUP \
		--query name \
		--output tsv 2>/dev/null)

	if [ -z "$STORAGE_EXISTS" ]; then
		az storage account create \
			--name $STORAGE_ACCOUNT \
			--resource-group $RESOURCE_GROUP \
			--location $LOCATION \
			--sku Standard_LRS
		check_error "Storage Account 생성 실패"
	fi

	# Storage Account connection string 가져오기
	local storage_conn_str=$(az storage account show-connection-string \
		--name $STORAGE_ACCOUNT \
		--resource-group $RESOURCE_GROUP \
		--query connectionString \
		--output tsv)
	check_error "Storage connection string 조회 실패"

	# deadletter 컨테이너 존재 여부 확인
	local container_exists=$(az storage container exists \
		--name $DEAD_LETTER \
		--connection-string "$storage_conn_str" \
		--query "exists" -o tsv)

	if [ "$container_exists" != "true" ]; then
		# deadletter 컨테이너 생성
		az storage container create \
			--name $DEAD_LETTER \
			--connection-string "$storage_conn_str" \
			--output none
		check_error "Storage container 생성 실패"
	else
		log "Deadletter 컨테이너가 이미 존재합니다"
	fi
}

# Database Secret 생성
setup_database_secrets() {
	log "Database Secret 생성 중..."

	# MongoDB Secret 생성
	kubectl create secret generic $MONGO_SECRET_NAME \
		--namespace $NAMESPACE \
		--from-literal=mongodb-root-password=$MONGODB_PASSWORD \
		--from-literal=MONGODB_PASSWORD=$MONGODB_PASSWORD \
		2>/dev/null || true

	# PostgreSQL Secret 생성
	kubectl create secret generic $POSTGRES_SECRET_NAME \
		--namespace $NAMESPACE \
		--from-literal=postgresql-password=$POSTGRES_PASSWORD \
		--from-literal=POSTGRES_PASSWORD=$POSTGRES_PASSWORD \
		2>/dev/null || true
}

# MongoDB 설정
setup_mongodb() {
	log "MongoDB 설정 중..."

	# MongoDB 초기화 스크립트 ConfigMap 생성
	cat << EOF | kubectl apply -f -
apiVersion: v1
kind: ConfigMap
metadata:
  name: mongo-init
  namespace: $NAMESPACE
data:
  mongo-init.js: |
    db = db.getSiblingDB('$MONGODB_DATABASE');
    db.createUser({
      user: '$MONGODB_USER',
      pwd: '$MONGODB_PASSWORD',
      roles: [{ role: 'readWrite', db: '$MONGODB_DATABASE' }]
    });
EOF

	# MongoDB StatefulSet 생성
	cat << EOF | kubectl apply -f -
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: $MONGODB_HOST
  namespace: $NAMESPACE
spec:
  serviceName: $MONGODB_HOST
  replicas: 1
  selector:
    matchLabels:
      app: mongodb
  template:
    metadata:
      labels:
        app: mongodb
    spec:
      containers:
      - name: mongodb
        image: mongo:4.4
        env:
        - name: MONGO_INITDB_ROOT_USERNAME
          value: $MONGODB_USER
        - name: MONGO_INITDB_ROOT_PASSWORD
          valueFrom:
            secretKeyRef:
              name: $MONGO_SECRET_NAME
              key: mongodb-root-password
        - name: MONGO_INITDB_DATABASE
          value: $MONGODB_DATABASE
        ports:
        - containerPort: 27017
        volumeMounts:
        - name: mongodb-data
          mountPath: /data/db
        - name: mongo-init
          mountPath: /docker-entrypoint-initdb.d
      volumes:
      - name: mongo-init
        configMap:
          name: mongo-init
  volumeClaimTemplates:
  - metadata:
      name: mongodb-data
    spec:
      accessModes: [ "ReadWriteOnce" ]
      resources:
        requests:
          storage: 1Gi
---
apiVersion: v1
kind: Service
metadata:
  name: $MONGODB_HOST
  namespace: $NAMESPACE
spec:
  selector:
    app: mongodb
  ports:
  - port: 27017
    targetPort: 27017
  type: ClusterIP
EOF
}

# PostgreSQL 설정
setup_postgresql() {
	log "PostgreSQL 설정 중..."

	cat << EOF | kubectl apply -f -
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: $POSTGRES_HOST
  namespace: $NAMESPACE
spec:
  serviceName: $POSTGRES_HOST
  replicas: 1
  selector:
    matchLabels:
      app: postgresql
  template:
    metadata:
      labels:
        app: postgresql
    spec:
      containers:
      - name: postgresql
        image: postgres:13
        env:
        - name: POSTGRES_USER
          value: $POSTGRES_USER
        - name: POSTGRES_PASSWORD
          valueFrom:
            secretKeyRef:
              name: $POSTGRES_SECRET_NAME
              key: postgresql-password
        - name: POSTGRES_DB
          value: $POSTGRES_DB
        ports:
        - containerPort: 5432
        volumeMounts:
        - name: postgresql-data
          mountPath: /var/lib/postgresql/data
          subPath: postgres
  volumeClaimTemplates:
  - metadata:
      name: postgresql-data
    spec:
      accessModes: [ "ReadWriteOnce" ]
      resources:
        requests:
          storage: 1Gi
---
apiVersion: v1
kind: Service
metadata:
  name: $POSTGRES_HOST
  namespace: $NAMESPACE
spec:
  selector:
    app: postgresql
  ports:
  - port: 5432
    targetPort: 5432
  type: ClusterIP
EOF
}

//service배포에 필요한 configmap과 secret 생성  
deploy_cm_secret() {
	local port=$1

	# gateway ConfigMap 생성
	cat << EOF | kubectl apply -f -
apiVersion: v1
kind: ConfigMap
metadata:
  name: gateway
  namespace: ${NAMESPACE}
data:

  # Rate Limiter
  RATE_LIMIT_FOR_PERIOD: "50"
  RATE_LIMIT_REFRESH_PERIOD: "1s"
  RATE_LIMIT_TIMEOUT: "1s"

  RATE_LIMIT_MART_FOR_PERIOD: "50"
  RATE_LIMIT_MART_REFRESH_PERIOD: "1s"
  RATE_LIMIT_MART_TIMEOUT: "0"

  RATE_LIMIT_CONVENIENCE_FOR_PERIOD: "100"
  RATE_LIMIT_CONVENIENCE_REFRESH_PERIOD: "1s"
  RATE_LIMIT_CONVENIENCE_TIMEOUT: "0"

  RATE_LIMIT_ONLINE_FOR_PERIOD: "30"
  RATE_LIMIT_ONLINE_REFRESH_PERIOD: "1s"
  RATE_LIMIT_ONLINE_TIMEOUT: "0"

  # Retry 설정
  RETRY_MAX_ATTEMPTS: "3"
  RETRY_WAIT_DURATION: "1s"
  RETRY_MART_MAX_ATTEMPTS: "5"
  RETRY_MART_WAIT_DURATION: "500ms"
  RETRY_CONVENIENCE_MAX_ATTEMPTS: "4"
  RETRY_CONVENIENCE_WAIT_DURATION: "750ms"
  RETRY_ONLINE_MAX_ATTEMPTS: "3"
  RETRY_ONLINE_WAIT_DURATION: "1s"

  # Circuit Breaker 설정
  CB_SLIDING_WINDOW_SIZE: "10"
  CB_FAILURE_RATE_THRESHOLD: "30"
  CB_WAIT_DURATION_IN_OPEN: "5000"
  CB_PERMITTED_CALLS_IN_HALF_OPEN: "5"
  CB_SLOW_CALL_DURATION_THRESHOLD: "1000"
  CB_SLOW_CALL_RATE_THRESHOLD: "30"

  # TIMEOUT
  CONNECTION_TIMEOUT: "30000"
  RESPONSE_TIMEOUT: "30000"
EOF

	# async ConfigMap 생성
	cat << EOF | kubectl apply -f -
apiVersion: v1
kind: ConfigMap
metadata:
  name: async
  namespace: $NAMESPACE
data:
  APP_NAME: "async-service"
  SERVER_PORT: "$port"
  RETRY_INTERVAL: "30000"
  RETRY_MAX_COUNT: "3"

  MONGODB_HOST: "$MONGODB_HOST"
  MONGODB_PORT: "$MONGODB_PORT"
  MONGODB_DATABASE: "$MONGODB_DATABASE"
  MONGODB_USER: "$MONGODB_USER"
EOF

	# point ConfigMap 생성
	cat << EOF | kubectl apply -f -
apiVersion: v1
kind: ConfigMap
metadata:
  name: point
  namespace: $NAMESPACE
data:
  APP_NAME: "point-service"
  SERVER_PORT: "$port"
  POSTGRES_HOST: "$POSTGRES_HOST"
  POSTGRES_PORT: "$POSTGRES_PORT"
  POSTGRES_DB: "$POSTGRES_DB"
  POSTGRES_USER: "$POSTGRES_USER"
EOF

	# Gateway secret 생성
	cat << EOF | kubectl apply -f -
apiVersion: v1
kind: Secret
metadata:
  name: gateway
  namespace: $NAMESPACE
type: Opaque
stringData:
  EVENTGRID_ENDPOINT: "$EVENTGRID_ENDPOINT"
  EVENTGRID_KEY: "$EVENTGRID_KEY"
  EVENTGRID_DLQ_TOPIC: "$GATEWAY_TOPIC"
EOF

}

# 이미지 빌드/업르도
create_image() {
	local service_name=$1
	log "${service_name} 이미지 빌드/업로드..."

	# JAR 빌드 (멀티프로젝트 빌드)
	./gradlew ${service_name}:clean ${service_name}:build -x test
	check_error "${service_name} jar 빌드 실패"

	# Dockerfile 생성
	cat > "${service_name}/Dockerfile" << EOF
FROM eclipse-temurin:17-jdk-alpine
COPY build/libs/${service_name}.jar app.jar
ENTRYPOINT ["java","-jar","/app.jar"]
EOF

	# 이미지 빌드
	cd "${service_name}"
	az acr build \
		--registry $ACR_NAME \
		--image "membership/${service_name}:v1" \
		--file Dockerfile \
		.
	cd ..
	check_error "${service_name} 이미지 빌드 실패"
}

# 서비스 배포
deploy_service() {
	local service_name=$1
  local replicas=$2
	local port=$3
	local fixed_ip=$4
  read -r req_cpu req_mem limit_cpu limit_mem <<< "$5"

	log "${service_name} 서비스 배포 시작..."

	# configmap name, secret name, image name, service type 계산
  local cm_name=""
	local secret_name=""
  local image_name=""
  local service_type=""

  if [[ "$service_name" =~ ^gateway.* ]]; then
      cm_name="gateway"
      secret_name="gateway"
      image_name="gateway"
      service_type="type: LoadBalancer"
  elif [[ "$service_name" =~ ^point.* ]]; then
      cm_name="point"
      secret_name="${POSTGRES_SECRET_NAME}"
      image_name="point"
      service_type="type: LoadBalancer"
  elif [[ "$service_name" =~ ^async.* ]]; then
      cm_name="async"
      secret_name="${MONGO_SECRET_NAME}" 
      image_name="async"
      service_type="type: LoadBalancer"
  fi

  # Deployment YAML 생성
  cat << EOF | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ${service_name}
  namespace: $NAMESPACE
spec:
  replicas: $replicas
  selector:
    matchLabels:
      app: $service_name
  template:
    metadata:
      labels:
        app: $service_name
    spec:
      containers:
      - name: $service_name
        image: ${ACR_NAME}.azurecr.io/membership/${image_name}:v1
        imagePullPolicy: Always
        ports:
        - containerPort: $port
        envFrom:
        - configMapRef:
            name: ${cm_name}
        - secretRef:
            name: ${secret_name}
        resources:
          requests:
            cpu: ${req_cpu}
            memory: ${req_mem}
          limits:
            cpu: ${limit_cpu}
            memory: ${limit_mem}
EOF

	# 서비스 생성
    local loadbalancer_ip=""
    if [ ! -z "$fixed_ip" ]; then
        loadbalancer_ip="loadBalancerIP: $fixed_ip"
    fi

	cat << EOF | kubectl apply -f -
apiVersion: v1
kind: Service
metadata:
  name: ${service_name}
  namespace: $NAMESPACE
spec:
  selector:
    app: $service_name
  ports:
  - protocol: TCP
    port: 80
    targetPort: $port
  $service_type
  $loadbalancer_ip
EOF

    # Deployment Ready 대기
    kubectl rollout status deployment/${service_name} -n $NAMESPACE
    check_error "${service_name} Deployment 준비 실패"
}

# Event Grid Subscriber 설정
setup_event_grid_subscriber() {
    log "Event Grid Subscriber 설정 중..."

    # Storage Account ID 가져오기
    local storage_id=$(az storage account show \
        --name $STORAGE_ACCOUNT \
        --resource-group $RESOURCE_GROUP \
        --query id \
        --output tsv)
    check_error "Storage Account ID 조회 실패"

    # Topic들을 하나의 subscription으로 통합
    subscription_exists=$(az eventgrid event-subscription show \
        --name "${NAME}-sub" \
        --source-resource-id $(az eventgrid topic show --name $GATEWAY_TOPIC -g $RESOURCE_GROUP --query "id" -o tsv) \
        --query "provisioningState" -o tsv 2>/dev/null)

    if [ "$subscription_exists" = "Succeeded" ]; then
        log "Event Grid Subscription이 이미 존재합니다"
    else
        log "Event Grid Subscription 생성 중..."

        # Gateway Topic Subscription 생성
        az eventgrid event-subscription create \
            --name "${NAME}-sub" \
            --source-resource-id $(az eventgrid topic show --name $GATEWAY_TOPIC -g $RESOURCE_GROUP --query "id" -o tsv) \
            --endpoint $SUB_ENDPOINT \
            --endpoint-type webhook \
            --included-event-types $EVENTGRID_EVENT_TYPES \
            --max-delivery-attempts 3 \
            --event-ttl 1440 \
            --deadletter-endpoint "${storage_id}/blobServices/default/containers/$DEAD_LETTER" \
            --output none
        check_error "Event Grid Subscriber 생성 실패"
    fi
}

# 결과 출력
print_results() {
    log "=== 배포 결과 ==="
    kubectl get all -n $NAMESPACE

    log "=== Gateway 서비스 URL ==="
    local ip=$(kubectl get svc gateway -n $NAMESPACE -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
    log "Gateway Service URL: http://${ip}"
    
    log "=== Event Grid 정보 ==="
    log "Gateway Topic: $GATEWAY_TOPIC"
    log "Event Grid Subscription Endpoint: ${SUB_ENDPOINT}"
}

# 메인 실행 함수
main() {
  if [ $# -ne 1 ]; then
      print_usage
      exit 1
  fi

  if [[ ! $1 =~ ^[a-z0-9]+$ ]]; then
      echo "Error: userid는 영문 소문자와 숫자만 사용할 수 있습니다."
      exit 1
  fi

  # 환경 설정
  setup_environment "$1"

  # 사전 체크
  check_azure_cli

  # 기존 k8s 리소스 삭제
  clear_resources

  # 공통 리소스 설정
  setup_common_resources

  # Database Secret 설정
  setup_database_secrets

  # Database 설정
  setup_mongodb
  setup_postgresql

  # 데이터베이스 준비 상태 대기
  log "데이터베이스 준비 상태 대기 중..."
  kubectl wait --for=condition=ready pod -l app=mongodb -n $NAMESPACE --timeout=300s
  kubectl wait --for=condition=ready pod -l app=postgresql -n $NAMESPACE --timeout=300s
  log "데이터베이스 준비 완료"

  # 이미지 빌드/배포
  create_image "async"
  create_image "gateway"
  create_image "point"

  # ConfigMap, Secret 생성 
  deploy_cm_secret "8080"

  # 서비스 배포
  declare -a resources=(
      "256m"    # req.cpu
      "256Mi"   # req.mem
      "512m"    # limit.cpu
      "512Mi"   # limit.mem
  )
  deploy_service "gateway" "1" "8080" "" "${resources[*]}"
  deploy_service "async" "1" "8080" "$ASYNC_PUBIP" "${resources[*]}"

  declare -a resources=(
      "256m"    # req.cpu
      "256Mi"   # req.mem
      "512m"    # limit.cpu
      "512Mi"   # limit.mem
  )
  deploy_service "point-mart" "1" "8080" "" "${resources[*]}"
  
  declare -a resources=(
      "512m"    # req.cpu
      "512Mi"   # req.mem
      "1024m"    # limit.cpu
      "1024Mi"   # limit.mem
  )  
  deploy_service "point-convenience" "1" "8080" "" "${resources[*]}"

  declare -a resources=(
      "100m"    # req.cpu
      "100Mi"   # req.mem
      "300m"    # limit.cpu
      "300Mi"   # limit.mem
  )  
  deploy_service "point-online" "1" "8080" "" "${resources[*]}"

  # Event Grid Subscriber 설정
  setup_event_grid_subscriber

  # 결과 출력
  print_results

  log "모든 리소스가 성공적으로 생성되었습니다."
}

# 스크립트 시작
main "$@"
