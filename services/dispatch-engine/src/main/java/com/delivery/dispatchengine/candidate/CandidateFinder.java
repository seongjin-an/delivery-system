package com.delivery.dispatchengine.candidate;

import com.delivery.common.RedisKeys;
import com.delivery.common.rider.RiderStateFields;
import com.delivery.common.rider.RiderStatus;
import com.delivery.dispatchengine.config.DispatchProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * DE-02 후보 검색. 가게 근처에서 지금 한가한 라이더를 점수순으로 뽑는다.
 *
 * <p>두 단계로 나뉜다. GEO 에는 좌표밖에 없어서 그 라이더가 지금 배달 중인지, 방금 제안을
 * 거절했는지를 모른다. 그건 {@code rider:state} 해시에 따로 있다.
 *
 * <pre>
 * GEOSEARCH 로 거리순 30명        ← 거리만 아는 단계
 *   ↓
 * 30명의 rider:state 를 한꺼번에   ← 상태를 붙이는 단계
 *   ↓
 * IDLE 인 사람만 남기고 점수순 상위 10명
 * </pre>
 *
 * <p>30명을 뽑는 건 배달 중인 사람이 걸러질 걸 감안한 여유분이다. 점심시간에는 절반 이상이
 * 배달 중이라 30명 중 12명만 남기도 한다.
 */
@Component
@RequiredArgsConstructor
public class CandidateFinder {

    private static final Logger log = LoggerFactory.getLogger(CandidateFinder.class);

    private final StringRedisTemplate redis;
    private final DispatchProperties properties;

    public List<Candidate> find(double storeLat, double storeLng, long now) {
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> nearby = searchNearby(storeLat, storeLng);
        if (nearby.isEmpty()) {
            return List.of();
        }

        List<String> riderIds = nearby.stream()
                .map(result -> result.getContent().getName())
                .toList();
        List<List<String>> states = readStates(riderIds);

        List<Candidate> candidates = new ArrayList<>(nearby.size());
        for (int i = 0; i < nearby.size(); i++) {
            Candidate candidate = toCandidate(nearby.get(i), states.get(i), now);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }

        candidates.sort(Comparator.comparingDouble(Candidate::score));
        return candidates.size() <= properties.maxCandidates()
                ? candidates
                : candidates.subList(0, properties.maxCandidates());
    }

    /**
     * {@code GEOSEARCH riders:online FROMLONLAT .. BYRADIUS 3000 m ASC COUNT 30 WITHDIST}
     *
     * <p>COUNT 와 ASC 를 반드시 같이 준다. 둘 다 줘야 레디스가 가까운 순으로 세다가 30명에서
     * 멈춘다. 안 주면 반경 안에 있는 500명을 전부 계산해서 실어 보낸다.
     */
    private List<GeoResult<RedisGeoCommands.GeoLocation<String>>> searchNearby(double lat, double lng) {
        RedisGeoCommands.GeoSearchCommandArgs args = RedisGeoCommands.GeoSearchCommandArgs
                .newGeoSearchArgs()
                .includeDistance()
                .sortAscending()
                .limit(properties.geoCount());

        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redis.opsForGeo().search(
                RedisKeys.RIDERS_GEO,
                // 레디스 GEO 는 경도가 먼저다. 순서를 바꾸면 엉뚱한 데를 뒤지는데 에러는 안 난다.
                GeoReference.fromCoordinate(new Point(lng, lat)),
                new Distance(properties.searchRadiusMeters(), Metrics.NEUTRAL),
                args);

        return results == null ? List.of() : results.getContent();
    }

    /**
     * 30명의 상태를 <b>파이프라인 한 번</b>으로 읽는다.
     *
     * <p>for 루프로 30번 왕복하면 로컬에서는 3ms 라 티가 안 나는데, 레디스가 다른 노드에 있으면
     * 왕복 한 번이 1ms 라 30ms 가 된다. 배차 하나에 30ms 를 상태 조회에만 쓰는 셈이다.
     */
    private List<List<String>> readStates(List<String> riderIds) {
        List<Object> raw = redis.executePipelined(
                (org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                    for (String riderId : riderIds) {
                        connection.hashCommands().hMGet(
                                RedisKeys.riderState(Long.parseLong(riderId)).getBytes(),
                                RiderStateFields.STATUS.getBytes(),
                                RiderStateFields.IDLE_SINCE.getBytes());
                    }
                    // 파이프라인은 반환값을 여기서 안 쓴다. 결과는 executePipelined 가 모아준다.
                    return null;
                });

        List<List<String>> states = new ArrayList<>(riderIds.size());
        for (Object row : raw) {
            states.add(row instanceof List<?> list
                    ? list.stream().map(v -> v == null ? null : v.toString()).toList()
                    : List.of());
        }
        return states;
    }

    private Candidate toCandidate(GeoResult<RedisGeoCommands.GeoLocation<String>> result,
                                  List<String> state, long now) {
        String riderIdText = result.getContent().getName();
        long riderId;
        try {
            riderId = Long.parseLong(riderIdText);
        } catch (NumberFormatException e) {
            // GEO 에 라이더 아이디가 아닌 게 들어가 있다. 배차를 멈출 일은 아니고 건너뛴다.
            log.warn("GEO 에 숫자가 아닌 멤버가 있다: {}", riderIdText);
            return null;
        }

        String status = state.isEmpty() ? null : state.get(0);
        if (!RiderStatus.parseOrOffline(status).isAvailable()) {
            return null;
        }

        double distanceKm = result.getDistance().getValue() / 1000.0;
        long waitMinutes = waitMinutes(state.size() > 1 ? state.get(1) : null, now);

        // 점수는 낮을수록 우선. 오래 기다린 사람에게 보너스를 주지 않으면 가게 앞 라이더만
        // 계속 콜을 먹고 조금 떨어진 사람은 하루 종일 논다.
        double waitBonus = Math.min(waitMinutes, properties.waitBonusCapMinutes()) * 0.1;
        return new Candidate(riderId, distanceKm, waitMinutes, distanceKm - waitBonus);
    }

    /**
     * idleSince 가 없으면 대기 보너스 0으로 둔다.
     *
     * <p>아직 geo-indexer(GI-01)가 이 필드를 안 쓰기 때문에 지금은 늘 0이다. 그래도 여기서
     * 예외를 던지지 않는 게 맞다 — 필드 하나 없다고 배차 전체가 멈추면 안 된다.
     */
    private static long waitMinutes(String idleSince, long now) {
        if (idleSince == null || idleSince.isBlank()) {
            return 0;
        }
        try {
            long since = Long.parseLong(idleSince);
            return Math.max(0, (now - since) / 60_000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
