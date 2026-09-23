package com.delivery.notificationworker.ratelimit;

import com.delivery.common.RedisKeys;
import com.delivery.notificationworker.config.PushProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 전역 초당 한도. 판정은 전부 token-bucket.lua 가 하고 여기는 부르기만 한다.
 */
@Component
@RequiredArgsConstructor
public class TokenBucket {

    private final StringRedisTemplate redis;
    private final RedisScript<Long> tokenBucketScript;
    private final PushProperties properties;

    /** 토큰 하나를 쓴다. 없으면 false */
    public boolean tryAcquire() {
        Long allowed = redis.execute(tokenBucketScript, List.of(RedisKeys.PUSH_RATE_BUCKET),
                Integer.toString(properties.globalRatePerSec()));
        return allowed != null && allowed == 1L;
    }

    /**
     * 토큰이 날 때까지 조금 기다려본다. 기능 정의서 NW-02 규칙 2번 — 100ms 씩 3회.
     *
     * <p>리스너 스레드가 그동안 멈춰 있는다. 일부러 그렇게 둔다. 한도에 걸렸다는 건 외부 API 가 더 못 받는다는
     * 뜻이라, 스레드가 놀든 말든 더 빨리 보낼 방법이 없다. 이게 시나리오 C 에서 "워커를 늘려도 안 빨라진다" 로 보인다.
     */
    public boolean acquireWithWait() throws InterruptedException {
        if (tryAcquire()) {
            return true;
        }
        for (int i = 0; i < properties.rateLimitWaits(); i++) {
            Thread.sleep(properties.rateLimitBackoff().toMillis());
            if (tryAcquire()) {
                return true;
            }
        }
        return false;
    }
}
