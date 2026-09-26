package com.delivery.geoindexer.mysql;

import com.delivery.common.event.RiderLocation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 2단계 실험: 레디스 GEOADD 대신 MySQL 공간 인덱스에 좌표를 쓴다.
 *
 * <p>배치 하나를 문장 하나로 보낸다. JDBC URL 에 rewriteBatchedStatements=true 를 켜둬서 드라이버가
 * 500건을 {@code INSERT ... VALUES (..),(..),...} 한 줄로 합쳐준다. 이게 없으면 500번 왕복하고
 * 500번 커밋한다. 레디스 파이프라인과 맞추려면 이 정도는 해야 공정하다.
 *
 * <p>배치 안에서 라이더별 마지막 좌표만 남기는 것도 RiderIndexer 와 같다.
 */
@Component
@ConditionalOnProperty(name = "delivery.geo.store", havingValue = "mysql")
public class MysqlPositionWriter {

    private static final String SQL = """
            INSERT INTO rider_position (rider_id, pos, updated_at)
            VALUES (?, ST_PointFromText(?, 4326), ?)
            ON DUPLICATE KEY UPDATE pos = VALUES(pos), updated_at = VALUES(updated_at)
            """;

    private final JdbcTemplate jdbc;

    public MysqlPositionWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int write(List<RiderLocation> locations, long now) {
        Map<Long, RiderLocation> latest = new LinkedHashMap<>();
        for (RiderLocation location : locations) {
            latest.put(location.riderId(), location);
        }
        Timestamp seenAt = new Timestamp(now);
        List<Object[]> rows = new ArrayList<>(latest.size());
        for (RiderLocation l : latest.values()) {
            // SRID 4326 은 (위도 경도) 순서다
            rows.add(new Object[]{l.riderId(), String.format(Locale.ROOT, "POINT(%.7f %.7f)", l.lat(), l.lng()), seenAt});
        }
        jdbc.batchUpdate(SQL, rows);
        return rows.size();
    }
}
