-- 2단계 실험: 레디스 GEO 대신 쓸 라이더 좌표 테이블.
--
-- SRID 4326 을 컬럼에 박아야 공간 인덱스를 탄다. 안 박으면 MySQL 8 은 인덱스를 만들어는 주지만
-- 옵티마이저가 안 쓴다(경고만 한 줄 남긴다).
-- MySQL 8 의 4326 은 축 순서가 (위도, 경도)다. 레디스 GEO(경도, 위도)와 반대라서 헷갈리기 딱 좋다.
DROP TABLE IF EXISTS rider_position;
CREATE TABLE rider_position (
    rider_id   BIGINT       NOT NULL PRIMARY KEY,
    pos        POINT        NOT NULL SRID 4326,
    updated_at DATETIME(3)  NOT NULL,
    SPATIAL INDEX idx_rider_position_pos (pos)
) ENGINE = InnoDB;
