package com.delivery.notificationworker;

import com.delivery.common.RedisKeys;
import com.delivery.notificationworker.config.PushProperties;
import com.delivery.notificationworker.config.RedisScriptConfig;
import com.delivery.notificationworker.ratelimit.TokenBucket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 토큰 버킷을 진짜 레디스에 돌려본다. 판정이 전부 Lua 안에 있어서 목으로는 볼 게 없다.
 *
 * <p>rate:push 키 하나를 개발용 레디스와 같이 쓴다. 테스트 앞뒤로 지운다. notification-worker 를 띄워둔 채로
 * 돌리면 그쪽이 토큰을 같이 써서 숫자가 흔들린다.
 */
class TokenBucketRedisTest {

    private static final String HOST = "localhost";
    private static final int PORT = 6380;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
            .withUserConfiguration(RedisScriptConfig.class)
            .withPropertyValues("spring.data.redis.host=" + HOST, "spring.data.redis.port=" + PORT);

    @BeforeAll
    static void requireRedis() {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(HOST, PORT), 300);
        } catch (IOException e) {
            assumeTrue(false, "레디스(" + HOST + ":" + PORT + ")가 없어서 건너뛴다. ./scripts/start.sh 로 띄우면 돈다");
        }
    }

    @AfterEach
    void cleanUp() {
        run(5, (bucket, redis) -> redis.delete(RedisKeys.PUSH_RATE_BUCKET));
    }

    @Test
    void letsThroughExactlyRatePerSecondThenRefuses() {
        run(5, (bucket, redis) -> {
            redis.delete(RedisKeys.PUSH_RATE_BUCKET);
            int allowed = 0;
            for (int i = 0; i < 8; i++) {
                if (bucket.tryAcquire()) {
                    allowed++;
                }
            }
            assertThat(allowed).isEqualTo(5);
        });
    }

    @Test
    void refillsOverTime() throws Exception {
        run(5, (bucket, redis) -> {
            redis.delete(RedisKeys.PUSH_RATE_BUCKET);
            for (int i = 0; i < 5; i++) {
                bucket.tryAcquire();
            }
            assertThat(bucket.tryAcquire()).isFalse();

            sleep(450);   // 초당 5개면 200ms 에 하나. 450ms 면 두 개가 찬다

            assertThat(bucket.tryAcquire()).isTrue();
            assertThat(bucket.tryAcquire()).isTrue();
            assertThat(bucket.tryAcquire()).isFalse();
        });
    }

    @Test
    void twoInstancesShareOneBudget() {
        // 인스턴스 두 대가 각자 TokenBucket 을 들고 있어도 합쳐서 5개다. 나눠 가지면 10개가 나간다
        run(5, (first, redis) -> run(5, (second, ignored) -> {
            redis.delete(RedisKeys.PUSH_RATE_BUCKET);
            int allowed = 0;
            for (int i = 0; i < 5; i++) {
                if (first.tryAcquire()) allowed++;
                if (second.tryAcquire()) allowed++;
            }
            assertThat(allowed).isEqualTo(5);
        }));
    }

    private void run(int ratePerSec, BiConsumer<TokenBucket, StringRedisTemplate> body) {
        contextRunner.run(context -> {
            StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            RedisScript<Long> script = context.getBean("tokenBucketScript", RedisScript.class);
            PushProperties properties = new PushProperties(ratePerSec, 0, 0, "", 3,
                    Duration.ofMillis(100), 3, Duration.ofMillis(100));
            body.accept(new TokenBucket(redis, script, properties), redis);
        });
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
