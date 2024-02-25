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

        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
            .readFrom(ReadFrom.REPLICA_PREFERRED)
            .build();

        RedisSentinelConfiguration sentinelConfig = new RedisSentinelConfiguration()
            .master(redisProperties.getSentinel().getMaster());

        redisProperties.getSentinel().getNodes()
            .forEach(s -> sentinelConfig.sentinel(s.split(":")[0], Integer.valueOf(s.split(":")[1])));
        sentinelConfig.setPassword(RedisPassword.of(redisProperties.getPassword()));
        sentinelConfig.setSentinelPassword(RedisPassword.of(redisProperties.getSentinel().getPassword()));

        return new LettuceConnectionFactory(sentinelConfig, clientConfig);
    }

}
