<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->
### TOC

- [什么是哨兵和哨兵模式？](#%E4%BB%80%E4%B9%88%E6%98%AF%E5%93%A8%E5%85%B5%E5%92%8C%E5%93%A8%E5%85%B5%E6%A8%A1%E5%BC%8F)
- [部署环境](#%E9%83%A8%E7%BD%B2%E7%8E%AF%E5%A2%83)
- [架构说明](#%E6%9E%B6%E6%9E%84%E8%AF%B4%E6%98%8E)
- [方案实施](#%E6%96%B9%E6%A1%88%E5%AE%9E%E6%96%BD)
- [方案验证](#%E6%96%B9%E6%A1%88%E9%AA%8C%E8%AF%81)
  - [Java](#java)
  - [Golang](#golang)
- [总结](#%E6%80%BB%E7%BB%93)

<!-- END doctoc generated TOC please keep comment here to allow auto update -->

Redis是目前业界事实标准的缓存中间件。我们在实际项目中也采用了Redis对登录令牌、用户信息和业务数据等做了缓存层来应对大流量高并发的访问请求，来减轻数据库压力提高系统可用性，同时降低请求延迟提升用户体验。虽然Redis本身性能非常优异，在开发和测试环境使用单机版Redis即可满足需求，但是生产环境还是建议采用集群方式进行部署，可以避免单点故障进一步提升系统整体的可用性。我们考虑过Redis的集群(cluster)和哨兵(sentinel)两种部署模式，从下表中的四个维度进行了对比：

| 模式 | 可用性 | 扩展性 | 复杂度 | 成本 |
| --- | --- | --- | --- | --- |
|  Redis Cluster| 需结合Sentinel | 高 | 高 | 高 |
|  Redis Sentinel| 高 | 仅读操作可以扩展 | 低 | 低 |

Redis集群相比哨兵模式的优势主要是具备数据分片能力，扩展性高，但结合我们的场景暂时还不需要考虑扩展性。Redis集群想实现高可用需要结合哨兵模式才可以，也就是集群中每个节点都要按哨兵模式来部署，slave实时复制master节点的数据，架构复杂度和部署运维成本高。综合考虑各种因素，我们选择了哨兵模式。

另一个问题就是基于主机部署还是基于k8s部署？其实不论是主机还是k8s都可以实现目标，主要还是看团队和项目的实际情况。如果团队里有专业的运维岗位或者有对shell和ansible脚本非常熟悉的同事，选择主机部署就可以。如果团队主推的是devops的研发模式，并且已经用上了高可用k8s集群，那么选择k8s部署就是最优选择。我们的情况属于后者，核心系统全部已经容器化，基于k8s来做CICD。所以我们最终采用了基于k8s的Redis哨兵高可用部署方案。

## 什么是哨兵和哨兵模式？
哨兵是可以用`redis-sentinel /etc/redis/sentinel.conf`命令直接启动的一个进程。`redis-sentinel`可执行文件是跟redis一起安装的。哨兵模式是一种实现redis高可用的部署模式，即部署一组哨兵构成一套分布式系统，实现redis主从集群的自动故障转移。具体来说，哨兵可以提供如下四种能力：

- 监控能力：哨兵可以持续监控redis主从节点是否正常工作；
- 通知能力：如果有redis实例出现什么问题，哨兵可以通过API通知到系统管理员或者其他程序；
- 自动故障转移：如果主节点出现故障，哨兵可以自动启动故障转移机制，提升一台redis从节点为新的主节点，使redis可以自动从故障中恢复继续提供服务；
- 配置中心：哨兵承担着配置中心的职责，客户端可以连接哨兵并查询到redis主节点的信息，如果发生故障转移，还可以收到新的主节点的信息；

自动故障转移的过程是这样的：每一台哨兵节点都会通过向redis主节点发送`PING`心跳命令，如果发现主节点失联，则会通过gossip的机制，向其他哨兵节点确认主节点是否失联，如果拿到`quorum`份确认信息（算上自己），则确定该主节点出现故障，发起类似raft的选举，当收到大多数哨兵的投票，则可以开始执行故障转移。首先按一定规则从全部从节点中选出最合适的一个，提升为主节点，然后更新其他从节点的配置，使得它们从新的主节点同步数据，最后通知客户端发生了故障转移并给到新的主节点的地址。

## 部署环境
本方案在高可用多架构k8s集群环境验证可行，以下是详情参数：

| 主机名 | 配置 | 角色 | k8s版本 | 操作系统 | kernel版本 | 容器运行时 |
| --- | --- | --- | --- | --- | --- | --- |
|  master1 |  16C32G | ectd,master | v1.18.12 | CentOS7 | 3.10.0 x86_64 | docker://19.3.9 |
|  master2 |  16C32G | ectd,master | v1.18.12 | CentOS7 | 3.10.0 x86_64 | docker://19.3.9 |
|  master3 |  16C32G | ectd,master | v1.18.12 | CentOS7 | 3.10.0 x86_64 | docker://19.3.9 |
|  node1 |  16C32G | worker | v1.18.12 | CentOS7 | 3.10.0 x86_64 | docker://19.3.9 |
|  node2 |  16C32G | worker | v1.18.12 | CentOS7 | 3.10.0 x86_64 | docker://19.3.9 |
|  node3 |  16C32G | worker | v1.18.12 | Kylin V10 | 4.19.90 aarch64  | docker://19.3.9  |

## 架构说明

下图为生产环境架构：

![image.png](https://p9-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/2dffbdb60f7a45728c577df7d9ce5e71~tplv-k3u1fbpfcp-jj-mark:0:0:0:0:q75.image#?w=3964&h=2524&s=671760&e=png&a=1&b=ffffff)

下图为开发环境架构，比上图增加了负载均衡器Metallb，用于暴露Sentinel和Redis服务给开发者本地环境，方便开发和自测工作：

![image.png](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/1fecd139f3fd40ada569a6e3f80a4690~tplv-k3u1fbpfcp-jj-mark:0:0:0:0:q75.image#?w=4404&h=2524&s=837694&e=png&a=1&b=ffffff)

k8s内部应用访问Redis直连k8s内部的Sentinel服务地址(图中绿底地址)即可。开发环境需要连接可以从k8s外部访问的ip:port地址(图中蓝底地址)。

Sentinel和redis实例都采用statefulset方式部署，在这里的好处主要是每个pod都可以有一个固定的访问地址，比如3个sentinel pod的访问地址分别是sentinel-0.sentinel，sentinel-1.sentinel，sentinel-2.sentinel，3个redis pod的访问地址分别是redis-0.redis，redis-1.redis，redis-2.redis。地址规则可以参考 https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/#stable-network-id 。3个redis实例为一主两从。我们还给所有的sentinel和redis实例配置了`podAntiAffinity`，确保每个pod都部署在不同的worker节点上，实现真正的高可用。

## 方案实施

完整方案包括以下配置和脚本文件，下文逐个说明：

```shell
.
├── resources
│   ├── redis-config.yml             # redis初始配置文件
│   ├── redis-scripts-config.yml     # redis和sentinel初始化脚本
│   ├── redis-secret.yml             # redis和sentinel的密码
│   ├── redis-services-dev-0.yml     # 开发环境使用的service
│   ├── redis-services-dev-1.yml     # 开发环境使用的service
│   ├── redis-services.yml           # 测试和正式环境使用的service
│   ├── redis-stateful.yml           # redis有状态pod部署文件
│   └── sentinel-stateful.yml        # sentinel有状态pod部署文件
├── startCluster-dev.sh              # 开发环境使用的部署脚本
└── startCluster.sh                  # 测试和生产环境使用的部署脚本
```

`redis-config.yml`文件用于redis实例的部署，`REDIS_NODES`需定义redis实例的地址，sentinel会通过这里配置的地址来与各redis实例通信，多个地址用英文逗号分隔。下文我们还会提到，```redis.conf```里的配置项可以按需修改。

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: redis-config-map
data:
  REDIS_NODES: "redis-0.redis,redis-1.redis,redis-2.redis"

  redis.conf: |
    bind 0.0.0.0
    protected-mode no
    port 6379
    tcp-backlog 511
    timeout 10
    tcp-keepalive 30
    daemonize no
    supervised no
    pidfile "/var/run/redis_6379.pid"
    loglevel notice
    logfile ""
    databases 16
    always-show-logo yes
    save ""
    stop-writes-on-bgsave-error yes
    rdbcompression yes
    rdbchecksum yes
    rdb-del-sync-files no
    dir "/data"
    replica-serve-stale-data yes
    replica-read-only yes
    repl-diskless-sync no
    repl-diskless-sync-delay 5
    repl-diskless-load disabled
    appendonly no
    repl-disable-tcp-nodelay no
    replica-priority 100
    acllog-max-len 128
```

`redis-scripts-config.yml`文件用于sentinel实例和redis实例的初始化。在statefulset文件里配置了initContainers参数，pod创建过程中会先启动init container来执行这个文件中配置的脚本，然后退出init container，启动真正需要的container。`redis-scripts-config.yml`文件中配置了两段脚本sentinel_init.sh和redis_init.sh。

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: redis-scripts-config-map
data:  

  sentinel_init.sh: |
    #!/bin/bash

    for i in ${REDIS_NODES//,/ }
    do
        echo "finding master at $i"
        MASTER=$(redis-cli --no-auth-warning --raw -h $i -a ${REDIS_PASSWORD} info replication | awk '{print $1}' | grep master_host: | cut -d ":" -f2)
        
        if [ "${MASTER}" == "" ]; then
            echo "no master found"
            MASTER=
        else
            echo "found ${MASTER}"
            break
        fi
        
    done

    echo "sentinel monitor mymaster ${MASTER} 6379 2" >> /tmp/master
    echo "port 5000
    sentinel resolve-hostnames yes
    sentinel announce-hostnames yes
    $(cat /tmp/master)
    sentinel down-after-milliseconds mymaster 1000
    sentinel failover-timeout mymaster 10000
    sentinel parallel-syncs mymaster 1
    sentinel sentinel-pass ${REDIS_PASSWORD}
    sentinel auth-pass mymaster ${REDIS_PASSWORD}
    requirepass ${REDIS_PASSWORD}
    sentinel announce-ip ${HOSTNAME}.sentinel
    sentinel announce-port 5000

    " > /etc/redis/sentinel.conf
    cat /etc/redis/sentinel.conf


  redis_init.sh: |
    #!/bin/bash

    cp /tmp/redis/redis.conf /etc/redis/redis.conf
    echo "requirepass ${REDIS_PASSWORD}" >> /etc/redis/redis.conf
    echo "masterauth ${REDIS_PASSWORD}" >> /etc/redis/redis.conf
    echo "replica-announce-ip ${HOSTNAME}.redis" >> /etc/redis/redis.conf
    echo "replica-announce-port 6379 " >> /etc/redis/redis.conf
    
    echo "finding master..."

    if [ "$(timeout 5 redis-cli -h sentinel -p 5000 -a ${REDIS_PASSWORD} ping)" != "PONG" ]; then

      echo "sentinel not found, defaulting to redis-0"

      if [ ${HOSTNAME} == "redis-0" ]; then
        echo "this is redis-0, not updating config..."
      else
        echo "updating redis.conf..."
        echo "repl-ping-replica-period 3" >> /etc/redis/redis.conf
        echo "slave-read-only no" >> /etc/redis/redis.conf
        echo "slaveof redis-0.redis 6379" >> /etc/redis/redis.conf
      fi

    else

      echo "sentinel found, finding master"
      MASTER="$(redis-cli -h sentinel -p 5000 -a ${REDIS_PASSWORD} sentinel get-master-addr-by-name mymaster | grep -E '(^redis-*)|([0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3})')"

      if [ "${HOSTNAME}.redis" == ${MASTER} ]; then
        echo "this is master, not updating config..."
      else
        echo "master found : ${MASTER}, updating redis.conf"
        echo "slave-read-only no" >> /etc/redis/redis.conf
        echo "slaveof ${MASTER} 6379" >> /etc/redis/redis.conf
        echo "repl-ping-replica-period 3" >> /etc/redis/redis.conf
      fi

    fi
```

sentinel_init.sh脚本的逻辑是先遍历所有的redis实例，执行`INFO`命令查找master节点，然后生成`/etc/redis/sentinel.conf`配置文件用于启动sentinel容器。

redis_init.sh脚本的逻辑是先拷贝`redis-config.yml`文件里的redis配置到`/etc/redis/redis.conf`文件，然后配置redis密码、ip和地址等信息，再向sentinel服务发送`PING`命令确认sentinel是否存在，如果不存在，则默认将自己配置为redis-0实例的slave节点，如果存在，则向sentinel服务发送`SENTINEL GET-MASTER-ADDR-BY-NAME`命令查询当前master节点信息并将自己配置为该master节点的slave节点。

`redis-secret.yml`文件用于配置sentinel和redis实例的密码。

```yaml
kind: Secret
apiVersion: v1
metadata:
  name: redis-secret
type: Opaque
stringData:
  REDIS_PASSWORD: "xxxxxx"
```

`redis-services-dev-0.yml`，`redis-services-dev-1.yml`和`redis-services.yml`这三个文件都是用于配置sentinel和redis服务的。包含`dev`字符的前两个文件用于开发环境，第三个文件用于生产环境。区别是生产环境的sentinel服务为ClusterIP类型，redis服务为Headless类型，不对集群外暴露服务，仅供集群内部的pod实例访问；开发环境的sentinel服务为LoadBalancer类型，redis服务配置了四个，一个为Headless类型，跟生产环境的相同，另外三个均为LoadBalancer类型且分别定向到三个redis实例，这样就确保了开发者本地客户端可以直连sentinel服务和所有redis实例。客户端的工作原理是先从sentinel服务查询master节点和slave节点的地址，然后客户端直连master节点(读写)或slave节点(只读)执行数据操作，且master节点会通过sentinel做failover，三个redis实例都有可能升为master节点，所以我们需要将所有redis实例也暴露到集群外。

`redis-services.yml`
```yaml
# Headless service so sentinel could access redisses using syntax <pod-name>.<service-name>

apiVersion: v1
kind: Service
metadata:
  name: redis
  labels:
    app: redis
    app.kubernetes.io/component: redis
    app.kubernetes.io/instance: redis
spec:
  clusterIP: None
  ports:
  - port: 6379
    targetPort: 6379
    name: redis
  selector:
    app: redis

---

# Sentinel service used for project pod connection

apiVersion: v1
kind: Service
metadata:
  name: sentinel
  labels:
    app: sentinel
    app.kubernetes.io/component: sentinel
    app.kubernetes.io/instance: sentinel
spec:
  type: ClusterIP
  sessionAffinity: None
  ports:
  - port: 5000
    targetPort: 5000
    name: sentinel
  selector:
    app: sentinel
```

`redis-services-dev-0.yml`
```yaml
# Headless service so sentinel could access redisses using syntax <pod-name>.<service-name>

apiVersion: v1
kind: Service
metadata:
  name: redis
  labels:
    app: redis
    app.kubernetes.io/component: redis
    app.kubernetes.io/instance: redis
spec:
  clusterIP: None
  ports:
  - port: 6379
    targetPort: 6379
    name: redis
  selector:
    app: redis

---
# Sentinel service used for project pod connection

apiVersion: v1
kind: Service
metadata:
  name: sentinel
  labels:
    app: sentinel
    app.kubernetes.io/component: sentinel
    app.kubernetes.io/instance: sentinel
  annotations:
    metallb.universe.tf/address-pool: pool-01
spec:
  type: LoadBalancer
  sessionAffinity: None
  ports:
  - port: 5000
    targetPort: 5000
    name: sentinel
  selector:
    app: sentinel
```

`redis-services-dev-1.yml`
```yaml
apiVersion: v1
kind: Service
metadata:
  name: redis-0
  labels:
    app: redis-0
    app.kubernetes.io/component: redis-0
    app.kubernetes.io/instance: redis-0
  annotations:
    metallb.universe.tf/address-pool: pool-01
spec:
  type: LoadBalancer
  ports:
  - port: 6379
    targetPort: 6379
    name: redis-0
  selector:
    statefulset.kubernetes.io/pod-name: redis-0

---

apiVersion: v1
kind: Service
metadata:
  name: redis-1
  labels:
    app: redis-1
    app.kubernetes.io/component: redis-1
    app.kubernetes.io/instance: redis-1
  annotations:
    metallb.universe.tf/address-pool: pool-01
spec:
  type: LoadBalancer
  ports:
  - port: 6379
    targetPort: 6379
    name: redis-1
  selector:
    statefulset.kubernetes.io/pod-name: redis-1

---

apiVersion: v1
kind: Service
metadata:
  name: redis-2
  labels:
    app: redis-2
    app.kubernetes.io/component: redis-2
    app.kubernetes.io/instance: redis-2
  annotations:
    metallb.universe.tf/address-pool: pool-01
spec:
  type: LoadBalancer
  ports:
  - port: 6379
    targetPort: 6379
    name: redis-2
  selector:
    statefulset.kubernetes.io/pod-name: redis-2
```

这里说明一下`redis-services-dev-1.yml`文件里的`selector`属性的`statefulset.kubernetes.io/pod-name: redis-x`标签是由k8s自动为我们打上的，不需要我们手动打标签。

`redis-stateful.yml`文件用于部署redis实例，配置了3副本和`podAntiAffinity`。注意副本数不能多于worker节点数，每个worker节点只能有一个redis pod，多出来的会一直pending；`serviceName`属性值必须与redis服务名保持一致，才能有`redis-x.redis`这样的地址。

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: redis
spec:
  serviceName: redis
  replicas: 3
  selector:
    matchLabels:
      app: redis
  template:
    metadata:
      labels:
        app: redis
    spec:
      initContainers:
      - name: config
        image: redis:7.2.4
        env:
          - name: REDIS_PASSWORD
            valueFrom:
              secretKeyRef:
                name: redis-secret
                key: REDIS_PASSWORD
        command: [ "sh", "-c", "/scripts/redis_init.sh" ]
        volumeMounts:
        - name: redis-config
          mountPath: /etc/redis/
        - name: config
          mountPath: /tmp/redis/
        - name: init-script
          mountPath: /scripts/
      containers:
      - name: redis
        image: redis:7.2.4
        command: ["redis-server"]
        args: ["/etc/redis/redis.conf"]
        ports:
        - containerPort: 6379
          name: redis
        volumeMounts:
        - name: data
          mountPath: /data
        - name: redis-config
          mountPath: /etc/redis/
      volumes:
      - name: data
        emptyDir: {}
      - name: redis-config
        emptyDir: {}
      - name: init-script
        configMap:
          name: redis-scripts-config-map
          defaultMode: 0777
          items:
          - key: redis_init.sh
            path: redis_init.sh
      - name: config
        configMap:
          name: redis-config-map
          items:
          - key: redis.conf
            path: redis.conf
      affinity:
        podAntiAffinity:
          requiredDuringSchedulingIgnoredDuringExecution:
          - labelSelector:
              matchExpressions:
              - key: app
                operator: In
                values:
                - redis
            topologyKey: kubernetes.io/hostname
```

`sentinel-stateful.yml`文件用于部署sentinel实例，配置了3副本和`podAntiAffinity`。注意副本数不能多于worker节点数，每个worker节点只能有一个sentinel pod，多出来的会一直pending；`serviceName`属性值必须与sentinel服务名保持一致，才能有`sentinel-x.sentinel`这样的地址。

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: sentinel
spec:
  serviceName: sentinel
  replicas: 3
  selector:
    matchLabels:
      app: sentinel
  template:
    metadata:
      labels:
        app: sentinel
    spec:
      initContainers:
      - name: config
        image: redis:7.2.4
        env:
          - name: REDIS_NODES
            valueFrom:
              configMapKeyRef:
                name: redis-config-map
                key: REDIS_NODES
          - name: REDIS_PASSWORD
            valueFrom:
              secretKeyRef:
                name: redis-secret
                key: REDIS_PASSWORD
        command: [ "sh", "-c", "/scripts/sentinel_init.sh" ]
        volumeMounts:
        - name: redis-config
          mountPath: /etc/redis/
        - name: init-script
          mountPath: /scripts/
      containers:
      - name: sentinel
        image: redis:7.2.4
        command: ["redis-sentinel"]
        args: ["/etc/redis/sentinel.conf"]
        ports:
        - containerPort: 5000
          name: sentinel
        volumeMounts:
        - name: redis-config
          mountPath: /etc/redis/
        - name: data
          mountPath: /data
      volumes:
      - name: init-script
        configMap:
          name: redis-scripts-config-map
          defaultMode: 0777
          items:
          - key: sentinel_init.sh
            path: sentinel_init.sh
      - name: redis-config
        emptyDir: {}
      - name: data
        emptyDir: {}
      affinity:
        podAntiAffinity:
          requiredDuringSchedulingIgnoredDuringExecution:
          - labelSelector:
              matchExpressions:
              - key: app
                operator: In
                values:
                - sentinel
            topologyKey: kubernetes.io/hostname
```

`startCluster-dev.sh`和`startCluster.sh`分别为开发环境和生产环境的一键部署脚本。两份脚本只有Services部分不同。

`startCluster-dev.sh`
```shell
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
```

`startCluster.sh`
```shell
FILE_PATH="${1:-"./resources"}"
NAMESPACE="redis-sentinel"

# Namespace
kubectl create ns $NAMESPACE

# Config files
kubectl apply -f "$FILE_PATH/redis-config.yml" -n $NAMESPACE
kubectl apply -f "$FILE_PATH/redis-scripts-config.yml" -n $NAMESPACE
kubectl apply -f "$FILE_PATH/redis-secret.yml" -n $NAMESPACE

# Services
kubectl apply -f "$FILE_PATH/redis-services.yml" -n $NAMESPACE

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
```

将全套配置和脚本打包发送到一台k8s节点上，执行`sh startCluster.sh`或`sh startCluster-dev.sh`命令后稍等10秒钟再查看部署是否成功。

在生产环境如果出现类似的输出即表示部署成功：
```shell
[root@master1 resources]# kubectl get all -n redis-sentinel -o wide
NAME             READY   STATUS    RESTARTS   AGE   IP              NODE                  NOMINATED NODE   READINESS GATES
pod/redis-0      1/1     Running   0          44m   x.x.x.x         node3                 <none>           <none>
pod/redis-1      1/1     Running   0          44m   x.x.x.x         node2                 <none>           <none>
pod/redis-2      1/1     Running   0          44m   x.x.x.x         node1                 <none>           <none>
pod/sentinel-0   1/1     Running   0          39m   x.x.x.x         node1                 <none>           <none>
pod/sentinel-1   1/1     Running   0          39m   x.x.x.x         node2                 <none>           <none>
pod/sentinel-2   1/1     Running   0          39m   x.x.x.x         node3                 <none>           <none>

NAME               TYPE        CLUSTER-IP      EXTERNAL-IP   PORT(S)    AGE   SELECTOR
service/redis      ClusterIP   None            <none>        6379/TCP   44m   app=redis
service/sentinel   ClusterIP   x.x.x.x         <none>        5000/TCP   36s   app=sentinel
```

在开发环境如果出现类似的输出即表示部署成功：
```shell
[root@master1 ~]# kubectl get all -n redis-sentinel -o wide
NAME                                READY   STATUS    RESTARTS   AGE     IP              NODE                  NOMINATED NODE   READINESS GATES
pod/redis-0                         1/1     Running   0          4d12h   x.x.x.x         node3                 <none>           <none>
pod/redis-1                         1/1     Running   0          21h     x.x.x.x         node1                 <none>           <none>
pod/redis-2                         1/1     Running   0          4d12h   x.x.x.x         node2                 <none>           <none>
pod/sentinel-0                      1/1     Running   0          4d13h   x.x.x.x         node3                 <none>           <none>
pod/sentinel-1                      1/1     Running   0          4d13h   x.x.x.x         node2                 <none>           <none>
pod/sentinel-2                      1/1     Running   0          4d13h   x.x.x.x         node1                 <none>           <none>

NAME                           TYPE           CLUSTER-IP       EXTERNAL-IP   PORT(S)          AGE     SELECTOR
service/redis                  ClusterIP      None             <none>        6379/TCP         4d13h   app=redis
service/redis-0                LoadBalancer   x.x.x.x          192.168.1.2   6379:30417/TCP   4d13h   statefulset.kubernetes.io/pod-name=redis-0
service/redis-1                LoadBalancer   x.x.x.x          192.168.1.3   6379:30753/TCP   4d13h   statefulset.kubernetes.io/pod-name=redis-1
service/redis-2                LoadBalancer   x.x.x.x          192.168.1.4   6379:30913/TCP   4d13h   statefulset.kubernetes.io/pod-name=redis-2
service/sentinel               LoadBalancer   x.x.x.x          192.168.1.1   5000:31882/TCP   4d13h   app=sentinel
```

## 方案验证
本节将分别演示Java和Golang两种技术栈的应用程序如何连接redis哨兵集群，并且验证杀掉master节点后sentinel是否可以正确实现failover。

### Java
Java示例工程采用JDK 1.8 + Spring boot 2.7 + Lettuce连接池技术栈。工程结构如下：

```shell
.
├── pom.xml
└── src
    └── main
        ├── java
        │   └── cloud
        │       └── unionj
        │           └── cache
        │               ├── RedisApplication.java    # 启动类
        │               └── config
        │                   └── RedisConfig.java     # 配置类
        └── resources
            └── application.yml                      # 配置文件

8 directories, 4 files
```

代码说明请参考行内注释。

`application.yml`
```yaml
spring:
  redis:
    password: xxxxxx               # redis密码
    sentinel:
      master: mymaster             # 集群名称
      nodes:
        - 192.168.1.1:5000         # sentinel连接地址
      password: xxxxxx             # sentinel密码
    lettuce:
      shutdown-timeout: 200ms
```

`RedisConfig.java`
```java
package cloud.unionj.cache.config;

import io.lettuce.core.ReadFrom;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

@RequiredArgsConstructor
@Configuration
public class RedisConfig {

    private final RedisProperties redisProperties;

    @Bean
    protected LettuceConnectionFactory redisConnectionFactory() {

        // 配置读写分离。从slave节点读
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
            .readFrom(ReadFrom.REPLICA_PREFERRED)
            .build();
        
        // 设置集群名称
        RedisSentinelConfiguration sentinelConfig = new RedisSentinelConfiguration()
            .master(redisProperties.getSentinel().getMaster());

        // 设置sentinel连接地址
        redisProperties.getSentinel().getNodes()
            .forEach(s -> sentinelConfig.sentinel(s.split(":")[0], Integer.valueOf(s.split(":")[1])));
        // 设置redis密码
        sentinelConfig.setPassword(RedisPassword.of(redisProperties.getPassword()));
        // 设置sentinel密码
        sentinelConfig.setSentinelPassword(RedisPassword.of(redisProperties.getSentinel().getPassword()));

        return new LettuceConnectionFactory(sentinelConfig, clientConfig);
    }

}
```

`RedisApplication.java`
```java
package cloud.unionj.cache;

import javax.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConnection;
import org.springframework.data.redis.connection.RedisServer;

@SpringBootApplication
public class RedisApplication implements CommandLineRunner {

    @Resource
    private RedisConnectionFactory redisConnectionFactory;

    private static Logger LOG = LoggerFactory.getLogger(RedisApplication.class);

    public static void main(String[] args) {
        LOG.info("STARTING THE APPLICATION");
        SpringApplication.run(RedisApplication.class, args);
        LOG.info("APPLICATION FINISHED");
    }

    @Override
    public void run(String... args) throws Exception {
        RedisSentinelConnection sentinelConnection = redisConnectionFactory.getSentinelConnection();
        // 每隔3秒打印一次master节点的host
        while (true) {
            sentinelConnection.masters().stream().map(RedisServer::getHost).forEach(host -> {
                LOG.info("=========== Current master is {} ===========", host);
            });
            Thread.sleep(3000);
        }
    }
}
```

我们首先通过idea启动程序，等待输出`=========== Current master is redis-x.redis ===========`后，执行命令`kubectl delete pod redis-x -n redis-sentinel`杀掉redis-x这个pod，观察sentinel是否可以正确实现failover。

```shell
...
2024-02-25 16:01:22.848  INFO 58711 --- [           main] o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat initialized with port(s): 8080 (http)
2024-02-25 16:01:22.853  INFO 58711 --- [           main] o.apache.catalina.core.StandardService   : Starting service [Tomcat]
2024-02-25 16:01:22.853  INFO 58711 --- [           main] org.apache.catalina.core.StandardEngine  : Starting Servlet engine: [Apache Tomcat/9.0.82]
2024-02-25 16:01:22.939  INFO 58711 --- [           main] o.a.c.c.C.[Tomcat].[localhost].[/]       : Initializing Spring embedded WebApplicationContext
2024-02-25 16:01:22.939  INFO 58711 --- [           main] w.s.c.ServletWebServerApplicationContext : Root WebApplicationContext: initialization completed in 729 ms
2024-02-25 16:01:23.447  INFO 58711 --- [           main] o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat started on port(s): 8080 (http) with context path ''
2024-02-25 16:01:23.455  INFO 58711 --- [           main] cloud.unionj.cache.RedisApplication      : Started RedisApplication in 1.566 seconds (JVM running for 1.958)
2024-02-25 16:01:23.455  INFO 58711 --- [           main] cloud.unionj.cache.RedisApplication      : Started RedisApplication in 1.566 seconds (JVM running for 1.958)
2024-02-25 16:01:23.674  INFO 58711 --- [           main] cloud.unionj.cache.RedisApplication      : =========== Current master is redis-2.redis ===========
2024-02-25 16:01:26.690  INFO 58711 --- [           main] cloud.unionj.cache.RedisApplication      : =========== Current master is redis-2.redis ===========
2024-02-25 16:01:29.715  INFO 58711 --- [           main] cloud.unionj.cache.RedisApplication      : =========== Current master is redis-2.redis ===========
2024-02-25 16:01:32.732  INFO 58711 --- [           main] cloud.unionj.cache.RedisApplication      : =========== Current master is redis-0.redis ===========
2024-02-25 16:01:35.761  INFO 58711 --- [           main] cloud.unionj.cache.RedisApplication      : =========== Current master is redis-0.redis ===========
2024-02-25 16:01:38.779  INFO 58711 --- [           main] cloud.unionj.cache.RedisApplication      : =========== Current master is redis-0.redis ===========
```

从上面的控制台输出的第12行和第13行，我们可以看到master节点成功实现了failover。我们的方案验证成功。

### Golang
Golang示例工程采用Go 1.22.0 + go-doudou v2.3.0 + go-redis技术栈。工程结构如下：
```shell
.
├── Dockerfile
├── app.yml                             # 配置文件
├── cache
│   └── redis.go                        # redis连接对象
├── cmd
│   └── main.go                         # main函数
├── config
│   └── config.go                       # 全局配置对象
├── dto
│   └── dto.go
├── go.mod
├── go.sum
├── redissentinelgo_openapi3.go
├── redissentinelgo_openapi3.json
├── svc.go
├── svcimpl.go
└── transport
    └── httpsrv
        ├── handler.go
        ├── handlerimpl.go
        └── middleware.go

6 directories, 15 files
```

首先看一下`app.yml`文件。`redissentinelgo`为配置项前缀，可在`config.go`文件中自定义修改。go-doudou从v2.3.0版本起支持yaml格式的配置文件里通过中横线`-`来设置列表项，如`redissentinelgo.redis.sentinel.nodes`配置项可以通过`-`配置多个多个sentinel地址。
```yaml
redissentinelgo:                     # 配置项前缀
  redis:
    password: xxxxxx                 # redis密码
    sentinel:
      master: mymaster               # 集群名称
      nodes:
        - 192.168.1.1:5000           # sentinel连接地址
      password: xxxxxx               # sentinel密码
```

再看一下`config.go`文件。请参考行内注释。
```go
/**
* Generated by go-doudou v2.3.0.
* You can edit it as your need.
 */
package config

import (
   "github.com/unionj-cloud/go-doudou/v2/framework/config"
   "github.com/unionj-cloud/go-doudou/v2/toolkit/envconfig"
   "github.com/unionj-cloud/go-doudou/v2/toolkit/zlogger"
)

var G_Config *Config

type Config struct {
   Redis struct {
      Password string
      Sentinel struct {
         Master   string
         // 必须加values_by:"index"结构体标签才可以启用yaml中的中横线语法
         Nodes    []string `values_by:"index" required:"true"`
         Password string
      }
   }
   config.Config
}

func init() {
   var conf Config
   // redissentinelgo可以改成任意值
   err := envconfig.Process("redissentinelgo", &conf)
   if err != nil {
      zlogger.Panic().Msgf("Error processing environment variables: %v", err)
   }
   G_Config = &conf
}

func LoadFromEnv() *Config {
   return G_Config
}
```

然后看一下`redis.go`文件。这里声明了两个全局变量`G_sentinel`和`G_rdb`。`G_sentinel`用于连接sentinel服务。`G_rdb`用于连接redis进行数据操作。

```go
package cache

import (
   "github.com/redis/go-redis/v9"
   "redis-sentinel-go/config"
)

var G_sentinel = redis.NewSentinelClient(&redis.Options{
   Addr:     config.G_Config.Redis.Sentinel.Nodes[0],
   Password: config.G_Config.Redis.Sentinel.Password,
})

var G_rdb = redis.NewFailoverClusterClient(&redis.FailoverOptions{
   MasterName:       config.G_Config.Redis.Sentinel.Master,
   SentinelAddrs:    config.G_Config.Redis.Sentinel.Nodes,
   SentinelPassword: config.G_Config.Redis.Sentinel.Password,
   Password:         config.G_Config.Redis.Password,
})
```

最后看一下`main.go`文件。与Java示例类似，我们每隔3秒发送一次`SENTINEL GET-MASTER-ADDR-BY-NAME`命令查询当前master节点信息，中途执行`kubectl delete pod redis-x -n redis-sentinel`命令杀死master节点，观察控制台输出的master节点有没有变化。如果有变化，证明sentinel可以正确实现failover。
```go
/**
* Generated by go-doudou v2.3.0.
* You can edit it as your need.
 */
package main

import (
   "context"
   "github.com/samber/lo"
   "github.com/unionj-cloud/go-doudou/v2/framework/rest"
   "github.com/unionj-cloud/go-doudou/v2/toolkit/zlogger"
   service "redis-sentinel-go"
   "redis-sentinel-go/cache"
   "redis-sentinel-go/config"
   "redis-sentinel-go/transport/httpsrv"
   "time"
)

func main() {
   conf := config.LoadFromEnv()
   svc := service.NewRedisSentinelGo(conf)
   handler := httpsrv.NewRedisSentinelGoHandler(svc)
   srv := rest.NewRestServer()
   srv.AddRoute(httpsrv.Routes(handler)...)
    
   // 这里我们每隔3秒发送一次SENTINEL GET-MASTER-ADDR-BY-NAME命令查询当前master节点信息
   for {
      addr, err := cache.G_sentinel.GetMasterAddrByName(context.Background(), config.G_Config.Redis.Sentinel.Master).Result()
      if err != nil {
         zlogger.Err(err).Msg(err.Error())
      }
      lo.ForEach(addr, func(item string, index int) {
         zlogger.Info().Msg(item)
      })
      time.Sleep(3 * time.Second)
   }

   srv.Run()
}
```

通过Goland IDE启动程序后，我们观察到如下日志输出：
```shell
GOROOT=/Users/wubin1989/go1.22.0 #gosetup
GOPATH=/Users/wubin1989/go #gosetup
/Users/wubin1989/go1.22.0/bin/go build -o /private/var/folders/3m/c4phkfbs0zjbx6j5ytprprbc0000gn/T/___go_build_redis_sentinel_go_cmd redis-sentinel-go/cmd #gosetup
/private/var/folders/3m/c4phkfbs0zjbx6j5ytprprbc0000gn/T/___go_build_redis_sentinel_go_cmd
2024/02/25 18:04:51 maxprocs: Leaving GOMAXPROCS=16: CPU quota undefined
2024-02-25 18:04:51 INF cmd/main.go:32 > redis-0.redis
2024-02-25 18:04:51 INF cmd/main.go:32 > 6379
2024-02-25 18:04:54 INF cmd/main.go:32 > redis-0.redis
2024-02-25 18:04:54 INF cmd/main.go:32 > 6379
2024-02-25 18:04:57 INF cmd/main.go:32 > redis-0.redis
2024-02-25 18:04:57 INF cmd/main.go:32 > 6379
2024-02-25 18:05:00 INF cmd/main.go:32 > redis-0.redis
2024-02-25 18:05:00 INF cmd/main.go:32 > 6379
2024-02-25 18:05:03 INF cmd/main.go:32 > redis-0.redis
2024-02-25 18:05:03 INF cmd/main.go:32 > 6379
2024-02-25 18:05:07 INF cmd/main.go:32 > redis-1.redis
2024-02-25 18:05:07 INF cmd/main.go:32 > 6379
2024-02-25 18:05:10 INF cmd/main.go:32 > redis-1.redis
2024-02-25 18:05:10 INF cmd/main.go:32 > 6379
```

从第14行和第16行我们可以看出master节点从redis-0变成了redis-1，证明sentinel可以成功实现failover。

## 总结
本文首先简要介绍了方案的背景和选型的过程，然后从什么是哨兵和哨兵模式、部署环境、架构说明、方案实施、方案验证等五个方面详细介绍了我们的基于k8s的Redis哨兵高可用部署方案。全套配置、脚本和示例代码已上传到[https://github.com/wubin1989/k8s-redis-sentinel](https://github.com/wubin1989/k8s-redis-sentinel)，可以拉下来代码动手实验一下。 该方案已经在线上生产环境成功实施并且稳定运行，希望可以给各位提供参考和启发。有任何疑问请留言，一起交流和进步！