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
        while (true) {
            sentinelConnection.masters().stream().map(RedisServer::getHost).forEach(host -> {
                LOG.info("=========== Current master is {} ===========", host);
            });
            Thread.sleep(3000);
        }
    }
}
