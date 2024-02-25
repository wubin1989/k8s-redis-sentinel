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
