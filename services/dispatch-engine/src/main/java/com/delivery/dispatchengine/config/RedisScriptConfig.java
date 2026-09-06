package com.delivery.dispatchengine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Lua 스크립트를 빈으로 올린다.
 *
 * <p>DefaultRedisScript 는 스크립트 본문의 SHA1 을 미리 계산해두고 EVALSHA 로 부른다.
 * 매번 스크립트 전문을 보내지 않아서, 배차마다 도는 이 자리에서는 그 차이가 쌓인다.
 */
@Configuration(proxyBeanMethods = false)
public class RedisScriptConfig {

    @Bean
    public RedisScript<Long> releaseLockScript() {
        return load("lua/release-lock.lua");
    }

    /** DE-04 수락 판정. 반환값 4가지를 그대로 HTTP 응답으로 가른다 */
    @Bean
    public RedisScript<Long> acceptOfferScript() {
        return load("lua/accept-offer.lua");
    }

    private static RedisScript<Long> load(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        // Long 으로 안 잡으면 반환값이 Integer 로 와서 -1 과 -2 를 가르는 switch 가 조용히 안 맞는다.
        script.setResultType(Long.class);
        return script;
    }
}
