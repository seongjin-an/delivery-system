package com.delivery.geoindexer.index;

import com.delivery.common.RedisKeys;
import com.delivery.common.Times;
import com.delivery.common.event.RiderLocation;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GI-01 위치 인덱싱. 한 배치를 레디스에 반영한다.
 *
 * <p>두 가지를 한다. 배치 안에서 <b>라이더별로 마지막 좌표만 남기고</b>, 남은 것들을
 * <b>파이프라인 한 번</b>으로 밀어 넣는다. 둘 다 왕복을 줄이려는 것이다.
 */
@Component
@RequiredArgsConstructor
public class RiderIndexer {

    /** 스크립트가 만지는 키 개수. 순서는 index-rider.lua 의 KEYS 주석 그대로다 */
    private static final int KEY_COUNT = 3;

    private final StringRedisTemplate redis;
    private final RedisScript<Long> indexRiderScript;

    public IndexResult index(List<RiderLocation> locations) {
        if (locations.isEmpty()) {
            return new IndexResult(0, 0, 0);
        }

        Collection<RiderLocation> latest = keepLatestPerRider(locations);
        long now = Times.now().toEpochMilli();

        List<Object> results = redis.executePipelined(pipeline(latest, now));

        int cameOnline = 0;
        for (Object result : results) {
            if (result instanceof Number number && number.longValue() == 1L) {
                cameOnline++;
            }
        }
        return new IndexResult(locations.size(), latest.size(), cameOnline);
    }

    /**
     * 같은 라이더의 좌표가 여러 개면 마지막 것만 남긴다.
     *
     * <p>뒤엣것이 이긴다. 파티션 키가 riderId 라서 한 라이더의 좌표는 전부 같은 파티션에
     * 순서대로 들어오고, 컨슈머는 그 순서 그대로 배치를 받는다. 그래서 리스트의 뒤쪽이
     * 곧 더 최근이다. {@code sentAt} 을 비교하지 않는 이유가 이거다 — 라이더 휴대폰 시계를
     * 믿는 것보다 카프카가 보장해주는 순서를 믿는 게 낫다.
     *
     * <p>{@link LinkedHashMap} 인 건 순서를 유지하려는 게 아니라 <b>재현 가능하게</b> 하려는
     * 것이다. HashMap 이면 파이프라인에 들어가는 순서가 실행마다 달라져서, 이상한 일이 생겼을 때
     * 같은 입력으로 다시 돌려봐도 같은 순서가 안 나온다.
     */
    private static Collection<RiderLocation> keepLatestPerRider(List<RiderLocation> locations) {
        Map<Long, RiderLocation> latest = new LinkedHashMap<>();
        for (RiderLocation location : locations) {
            latest.put(location.riderId(), location);
        }
        return latest.values();
    }

    /**
     * 라이더 수만큼 EVAL 을 한 번에 실어 보낸다.
     *
     * <p>EVALSHA 가 아니라 EVAL 이다. 스크립트 본문을 매번 같이 보내는 셈이라 바이트로는
     * 손해인데(라이더 한 명당 1KB 남짓), 파이프라인 안에서는 이게 맞다. EVALSHA 를 쓰려면
     * 서버에 스크립트가 올라가 있어야 하고, 없으면 NOSCRIPT 가 돌아온다. 그런데 파이프라인은
     * 결과를 맨 끝에 한꺼번에 받아서 <b>중간에 NOSCRIPT 를 알아채고 다시 보낼 방법이 없다.</b>
     * 레디스를 재시작하거나 SCRIPT FLUSH 가 한 번 돌면 그 배치가 통째로 날아간다.
     *
     * <p>스프링의 {@code RedisTemplate.execute(script, ...)} 도 파이프라인 안에서는 같은 이유로
     * EVAL 로 떨어진다. 여기서는 그걸 손으로 명시해서 보이게 해둔 것뿐이다.
     */
    private RedisCallback<Object> pipeline(Collection<RiderLocation> latest, long now) {
        byte[] script = indexRiderScript.getScriptAsString().getBytes(StandardCharsets.UTF_8);
        byte[] geoKey = bytes(RedisKeys.RIDERS_GEO);
        byte[] heartbeatKey = bytes(RedisKeys.RIDERS_HEARTBEAT);
        byte[] seenAt = bytes(Long.toString(now));

        return connection -> {
            for (RiderLocation location : latest) {
                connection.scriptingCommands().eval(
                        script, ReturnType.INTEGER, KEY_COUNT,
                        geoKey,
                        bytes(RedisKeys.riderState(location.riderId())),
                        heartbeatKey,
                        bytes(Long.toString(location.riderId())),
                        bytes(Double.toString(location.lng())),
                        bytes(Double.toString(location.lat())),
                        seenAt);
            }
            // 파이프라인은 여기서 값을 안 쓴다. 결과는 executePipelined 가 모아준다.
            return null;
        };
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
