package com.delivery.orderapi.domain;

import com.delivery.common.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 같은 요청이 두 번 와도 주문이 두 개 안 생기게 막는다. 기능 정의서 3.7.
 *
 * <p>레디스에 `SET idem:order:{key} {orderId} NX EX 3600` 을 건다. NX 라서 먼저 온 놈만
 * 성공하고, 뒤에 온 놈은 실패하면서 앞사람이 넣어둔 orderId 를 읽어간다.
 *
 * <p>왜 DB 유니크 제약이 아니라 레디스냐면, 주문이 실제로 만들어지기 <b>전에</b> 막아야 해서다.
 * DB 로 하면 INSERT 를 일단 시도해보고 제약에 걸리는 걸 확인하는 셈인데, 그러면 재시도 폭풍이
 * 그대로 DB 로 간다. 모바일 앱이 응답을 못 받고 3번씩 재시도하는 상황을 생각하면 차이가 커진다.
 *
 * <p>TTL 을 1시간으로 둔 건 "네트워크가 끊겨서 앱이 다시 보내는" 창을 덮으면 충분해서다.
 * 영원히 들고 있으면 레디스 메모리가 계속 늘어난다.
 */
@Component
@RequiredArgsConstructor
public class IdempotencyStore {

    private static final Duration TTL = Duration.ofHours(1);

    private final StringRedisTemplate redis;

    /** 이 키를 처음 잡은 사람이면 true. 이미 누가 잡았으면 false */
    public boolean reserve(String key, String orderId) {
        return Boolean.TRUE.equals(
                redis.opsForValue().setIfAbsent(RedisKeys.idempotency(key), orderId, TTL));
    }

    public Optional<String> findOrderId(String key) {
        return Optional.ofNullable(redis.opsForValue().get(RedisKeys.idempotency(key)));
    }

    /**
     * 잡아뒀던 키를 놓는다. 주문 저장이 실패했을 때만 부른다.
     *
     * <p>이걸 안 하면 DB 가 잠깐 흔들려서 실패한 요청의 멱등키가 한 시간 동안 남는다.
     * 손님이 다시 주문 버튼을 눌러도 "이미 처리된 요청" 으로 막히고, 정작 주문은 어디에도 없다.
     */
    public void release(String key) {
        redis.delete(RedisKeys.idempotency(key));
    }
}
