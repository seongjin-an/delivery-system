package com.delivery.common.geo;

import com.delivery.common.exception.BusinessException;
import com.delivery.common.exception.ErrorCode;

/**
 * 좌표 검증과 거리 계산. 기능 정의서 3.3.
 *
 * <p>범위를 한국으로 못 박아 둔 이유가 있다. 시뮬레이터가 좌표를 랜덤으로 만드는데
 * 부호를 하나 잘못 뒤집으면 태평양 한가운데 라이더가 생긴다. 그러면 GEOSEARCH 반경 안에
 * 아무도 안 잡혀서 "배차가 왜 다 실패하지" 를 한참 들여다보게 된다.
 * 들어오는 입구에서 잘라내는 게 훨씬 싸다.
 */
public final class Coordinates {

    public static final double MIN_LAT = 33.0;
    public static final double MAX_LAT = 39.0;
    public static final double MIN_LNG = 124.0;
    public static final double MAX_LNG = 132.0;

    /** 지구 평균 반지름(m). 하버사인 공식에 쓴다 */
    private static final double EARTH_RADIUS_M = 6_371_008.8;

    public static boolean isValid(double lat, double lng) {
        // NaN 은 어떤 비교를 해도 false 라서 아래 범위 검사에 자동으로 걸린다.
        // 무한대도 마찬가지. 따로 검사할 필요는 없다.
        return lat >= MIN_LAT && lat <= MAX_LAT && lng >= MIN_LNG && lng <= MAX_LNG;
    }

    /**
     * 범위를 벗어나면 400 INVALID_COORDINATE 로 끊는다.
     * 컨트롤러든 컨슈머든 좌표를 받는 곳이면 제일 먼저 이걸 부른다.
     */
    public static void validate(double lat, double lng) {
        if (!isValid(lat, lng)) {
            throw new BusinessException(
                    ErrorCode.INVALID_COORDINATE,
                    "좌표가 서비스 지역을 벗어났어요 (lat=%s, lng=%s)".formatted(lat, lng));
        }
    }

    /**
     * 두 좌표 사이 직선거리(m). location-ingest 의 이동거리 필터(15m)가 이걸 쓴다.
     *
     * <p>하버사인이라 지구를 완전한 구로 친다. 실제 타원체와 0.5% 쯤 어긋나는데,
     * 15m 를 넘었는지만 보면 되는 자리라 그 정도 오차는 아무 문제가 안 된다.
     */
    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    private Coordinates() {
    }
}
