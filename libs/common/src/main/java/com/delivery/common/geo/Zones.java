package com.delivery.common.geo;

/**
 * 좌표를 존 문자열로 접는다. 기능 정의서 3.4.
 *
 * <p>위경도를 0.01도(대략 1.1km) 격자로 내려서 {@code Z{위도*100}_{경도*100}} 로 만든다.
 * 예를 들어 (37.5665, 126.9780) 은 {@code Z3756_12697} 이 된다.
 *
 * <p>zoneId 는 클라이언트가 안 보낸다. 서버가 좌표로 계산한다.
 * 클라이언트를 믿으면 같은 자리인데 앱 버전마다 다른 존이 찍히는 일이 생긴다.
 *
 * <p>지금은 지표 라벨이랑 로그에만 쓴다. 확장 실험 D 에서 배차 락을 주문 단위가 아니라
 * 존 단위로 샤딩할 때 이 값이 락 키가 된다. 그때 가서 규칙을 바꾸면 기존 로그랑 안 맞아서,
 * 미리 여기 고정해 두는 것이다.
 */
public final class Zones {

    /** 격자 한 칸의 크기(도). 0.01도면 위도 방향으로 약 1.1km */
    private static final double GRID = 0.01;

    public static String of(double lat, double lng) {
        return "Z" + cell(lat) + "_" + cell(lng);
    }

    /**
     * 0.01도 격자로 내림. 반올림이 아니라 내림이라야 한 칸 안의 좌표가 전부 같은 존이 된다.
     *
     * <p>double 로 곱하면 37.57 * 100 이 3756.9999... 가 되는 자리가 있어서
     * Math.floor 가 3756 을 주고 경계에서 존이 밀린다. 그래서 곱한 뒤 아주 작은 값을 더해
     * 부동소수점 찌꺼기만 걷어낸다.
     */
    private static long cell(double degree) {
        return (long) Math.floor(degree / GRID + 1e-9);
    }

    private Zones() {
    }
}
