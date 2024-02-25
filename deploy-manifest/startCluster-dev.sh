FILE_PATH="${1:-"./resources"}"
NAMESPACE="redis-sentinel"

# Namespace
kubectl create ns $NAMESPACE

# Config files
kubectl apply -f "$FILE_PATH/redis-config.yml" -n $NAMESPACE
kubectl apply -f "$FILE_PATH/redis-scripts-config.yml" -n $NAMESPACE
kubectl apply -f "$FILE_PATH/redis-secret.yml" -n $NAMESPACE

# Services
kubectl apply -f "$FILE_PATH/redis-services-dev-0.yml" -n $NAMESPACE
kubectl apply -f "$FILE_PATH/redis-services-dev-1.yml" -n $NAMESPACE

# Redis
kubectl apply -f "$FILE_PATH/redis-stateful.yml" -n $NAMESPACE

# TODO 有问题：当只有一个redis pod满足condition=ready条件的时候就往下执行启动Sentinel的命令了，导致Sentinel的pod启动失败
# kubectl wait --namespace=$NAMESPACE \
#              --for=condition=ready pod \
#              --selector=app=redis \
#              --timeout=280s

sleep 10

# Sentinel
# 等待redis的pod都变为running状态以后，再执行下面的命令
kubectl apply -f "$FILE_PATH/sentinel-stateful.yml" -n $NAMESPACE