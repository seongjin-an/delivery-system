package com.delivery.geoindexer.geo;

import com.delivery.common.RedisKeys;
import com.delivery.common.Times;
import com.delivery.common.rider.RiderStateFields;
import com.delivery.common.rider.RiderStatus;
import com.delivery.geoindexer.config.SweepProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GI-03 라이더 한 명의 레디스 흔적을 전부 모은다. 기능 정의서 GI-03.
 *
 * <p>배차가 안 될 때 사람이 머릿속으로 맞춰보는 조건을 그대로 코드로 옮겼다. 후보가 되려면 DE-02 기준으로
 * 네 가지가 다 맞아야 한다 — GEO 에 있고, status 가 IDLE 이고, 좌표가 30초 안에 왔고, 다른 주문이 찜하고 있지 않다.
 * 하나라도 어긋나면 blockers 에 이유가 한 줄씩 들어간다. redis-cli 로 키 네 개를 하나씩 치는 대신 이걸 한 번 부른다.
 */
@Component
@RequiredArgsConstructor
public class RiderStateReader {

    private final StringRedisTemplate redis;
    private final SweepProperties sweepProperties;

    /**
     * @param state         rider:state 해시 그대로
     * @param position      riders:online 의 좌표. GEO 에 없으면 null
     * @param heartbeatAgoMs 마지막 좌표가 들어온 지 몇 ms. heartbeat 에 없으면 null
     * @param lockedByOrder lock:rider 값(찜한 주문). 없으면 null
     * @param candidate     지금 새 주문이 들어오면 후보로 뽑힐 수 있나
     * @param blockers      못 뽑히는 이유. candidate 면 비어 있다
     */
    public record RiderSnapshot(
            long riderId,
            Map<String, String> state,
            Position position,
            Long heartbeatAgoMs,
            String lockedByOrder,
            boolean candidate,
            List<String> blockers
    ) {
    }

    public record Position(double lat, double lng) {
    }

    public RiderSnapshot read(long riderId) {
        String member = Long.toString(riderId);
        Map<String, String> state = new LinkedHashMap<>();
        redis.opsForHash().entries(RedisKeys.riderState(riderId))
                .forEach((k, v) -> state.put(k.toString(), v.toString()));

        List<Point> points = redis.opsForGeo().position(RedisKeys.RIDERS_GEO, member);
        Point point = points == null || points.isEmpty() ? null : points.get(0);
        Double heartbeat = redis.opsForZSet().score(RedisKeys.RIDERS_HEARTBEAT, member);
        Long heartbeatAgo = heartbeat == null ? null : Times.now().toEpochMilli() - heartbeat.longValue();
        String lock = redis.opsForValue().get(RedisKeys.riderLock(riderId));

        List<String> blockers = new ArrayList<>();
        if (state.isEmpty()) {
            blockers.add("rider:state 가 없다. 좌표가 한 번도 안 들어왔거나 GI-01 이 못 받았다");
        }
        if (point == null) {
            blockers.add("riders:online(GEO) 에 없다. GEOSEARCH 에 안 잡힌다");
        }
        String status = state.get(RiderStateFields.STATUS);
        if (!state.isEmpty() && !RiderStatus.IDLE.name().equals(status)) {
            blockers.add("status 가 " + status + " 이라서 못 뽑힌다. 후보는 IDLE 만 뽑는다");
        }
        if (heartbeatAgo != null && heartbeatAgo > sweepProperties.offlineAfter().toMillis()) {
            blockers.add("좌표가 " + heartbeatAgo / 1000 + "초째 안 왔다. 다음 오프라인 정리(GI-02) 때 빠진다");
        }
        if (lock != null) {
            blockers.add("lock:rider 를 주문 " + lock + " 이 쥐고 있다. 찜이 풀릴 때까지 다른 주문이 못 잡는다");
        }
        return new RiderSnapshot(riderId, state, point == null ? null : new Position(point.getY(), point.getX()),
                heartbeatAgo, lock, blockers.isEmpty(), blockers);
    }
}
