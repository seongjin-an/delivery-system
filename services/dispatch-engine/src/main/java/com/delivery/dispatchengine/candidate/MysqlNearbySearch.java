package com.delivery.dispatchengine.candidate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;

/**
 * 2단계 실험: GEOSEARCH 대신 MySQL 공간 인덱스로 가까운 라이더를 찾는다.
 *
 * <p>두 단계로 한다. 공간 인덱스는 "이 네모 안에 있나" 만 빨리 답해준다. 그래서 반경을 감싸는 네모로
 * 먼저 거르고(MBRContains, 인덱스를 탄다), 남은 것만 실제 거리를 계산해서 정렬한다.
 * 원으로 바로 거르면(ST_Distance_Sphere &lt;= r) 인덱스를 못 타고 테이블 전체를 훑는다.
 *
 * <p>레디스 GEO 에는 온라인인 라이더만 있다(GI-02 가 빼준다). 여기는 그런 정리가 없어서 updated_at 으로
 * 30초 안에 좌표를 보낸 사람만 남긴다. GI-02 의 offline-after 와 같은 값이다.
 */
@Component
@ConditionalOnProperty(name = "delivery.dispatch.geo-store", havingValue = "mysql")
public class MysqlNearbySearch {

    private static final double METERS_PER_DEGREE = 111_320.0;

    // SRID 4326 은 축 순서가 (위도 경도)다. WKT 도 그 순서로 쓴다.
    private static final String SQL = """
            SELECT rider_id, ST_Distance_Sphere(pos, ST_PointFromText(?, 4326)) AS d
              FROM rider_position
             WHERE MBRContains(ST_GeomFromText(?, 4326), pos)
               AND updated_at > ?
            HAVING d <= ?
             ORDER BY d
             LIMIT ?
            """;

    private final JdbcTemplate jdbc;
    /** 좌표 쓰기를 끄고 읽기만 잴 때는 늘린다. 안 그러면 30초 뒤 후보가 0명이 된다 */
    private final long onlineWithinMs;

    public MysqlNearbySearch(JdbcTemplate jdbc,
                             @Value("${delivery.dispatch.mysql-online-within:30s}") java.time.Duration onlineWithin) {
        this.jdbc = jdbc;
        this.onlineWithinMs = onlineWithin.toMillis();
    }

    public List<Nearby> search(double lat, double lng, double radiusMeters, int limit, long now) {
        double dLat = radiusMeters / METERS_PER_DEGREE;
        double dLng = radiusMeters / (METERS_PER_DEGREE * Math.cos(Math.toRadians(lat)));
        String center = String.format(Locale.ROOT, "POINT(%.7f %.7f)", lat, lng);
        String box = String.format(Locale.ROOT,
                "POLYGON((%1$.7f %2$.7f, %1$.7f %4$.7f, %3$.7f %4$.7f, %3$.7f %2$.7f, %1$.7f %2$.7f))",
                lat - dLat, lng - dLng, lat + dLat, lng + dLng);
        return jdbc.query(SQL,
                (rs, i) -> new Nearby(rs.getLong(1), rs.getDouble(2)),
                center, box, new Timestamp(now - onlineWithinMs), radiusMeters, limit);
    }
}
