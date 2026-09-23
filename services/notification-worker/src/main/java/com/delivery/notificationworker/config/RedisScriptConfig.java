package com.delivery.notificationworker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 토큰 버킷 스크립트. rate:push 를 쓰는 건 notification-worker 뿐이라 libs/common 이 아니라 여기 둔다
 * (geo-indexer 의 index-rider.lua 와 같은 판단).
 */
@Configuration(proxyBeanMethods = false)
public class RedisScriptConfig {

    @Bean
    public RedisScript<Long> tokenBucketScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/token-bucket.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
