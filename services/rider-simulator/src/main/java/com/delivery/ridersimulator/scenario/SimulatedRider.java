package com.delivery.ridersimulator.scenario;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 가상 라이더 한 명의 위치. 기능 정의서 SM-01 규칙 2번.
 *
 * <p>방향을 조금씩 틀면서 걷는다. 매번 완전히 무작위 방향으로 가도 이동거리 필터는 통과한다
 * (필터는 직전에 발행한 점과 비교하고, 한 걸음이 16.7m 라서). 문제는 궤적이다. 무작위로 걸으면
 * 걸음 수의 제곱근만큼만 멀어져서, 10분(200걸음) 동안 처음 자리에서 240m 쯤밖에 못 벗어난다.
 * 그러면 라이더 분포가 처음 뿌린 그대로 굳어서, 어느 동네는 10분 내내 후보가 한 명도 없다.
 * 한 번에 30도까지만 틀면 방향이 평균 22걸음쯤 이어져서, 같은 200걸음에 1.5km 쯤 간다. 여섯 배 남짓이다.
 *
 * <p>좌표는 이 라이더의 가상 스레드 하나만 바꾼다. 다른 스레드는 안 읽는다.
 */
public final class SimulatedRider {

    private static final double METERS_PER_DEG_LAT = 111_320;
    /** 한 번에 트는 최대 각도 */
    private static final double MAX_TURN_RAD = Math.toRadians(30);

    private final long riderId;
    private final double centerLat;
    private final double centerLng;
    private final double spreadMeters;

    private double lat;
    private double lng;
    private double heading;

    SimulatedRider(long riderId, double centerLat, double centerLng, double spreadKm) {
        this.riderId = riderId;
        this.centerLat = centerLat;
        this.centerLng = centerLng;
        this.spreadMeters = spreadKm * 1000;

        // 원 안에 고르게 뿌린다. 반지름을 그냥 무작위로 뽑으면 가운데에 몰린다 (넓이는 반지름의 제곱에 비례한다)
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double r = spreadMeters * Math.sqrt(random.nextDouble());
        double theta = random.nextDouble(2 * Math.PI);
        this.lat = centerLat + r * Math.cos(theta) / METERS_PER_DEG_LAT;
        this.lng = centerLng + r * Math.sin(theta) / metersPerDegLng(centerLat);
        this.heading = random.nextDouble(2 * Math.PI);
    }

    /** stepMeters 만큼 걷는다. 원 밖으로 나가려고 하면 가운데 쪽으로 방향을 돌린다 */
    void walk(double stepMeters) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        heading += random.nextDouble(-MAX_TURN_RAD, MAX_TURN_RAD);

        double northMeters = (lat - centerLat) * METERS_PER_DEG_LAT;
        double eastMeters = (lng - centerLng) * metersPerDegLng(centerLat);
        if (Math.hypot(northMeters, eastMeters) > spreadMeters) {
            heading = Math.atan2(-eastMeters, -northMeters);
        }

        lat += stepMeters * Math.cos(heading) / METERS_PER_DEG_LAT;
        lng += stepMeters * Math.sin(heading) / metersPerDegLng(lat);
    }

    private static double metersPerDegLng(double atLat) {
        return METERS_PER_DEG_LAT * Math.cos(Math.toRadians(atLat));
    }

    public long riderId() {
        return riderId;
    }

    double lat() {
        return lat;
    }

    double lng() {
        return lng;
    }
}
