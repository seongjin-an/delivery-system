package com.delivery.ridersimulator.scenario;

import com.delivery.ridersimulator.config.SimulatorProperties;

/**
 * 한 번 돌릴 때 쓰는 값. POST /sim/start 본문이 우선이고, 빠진 건 application.yml 기본값으로 채운다.
 *
 * <p>필드가 전부 박싱 타입인 이유: 본문에서 안 보낸 것과 0 을 보낸 것을 가르려고. acceptRate 를 0 으로 보내면
 * "아무도 안 받는다"(1단계 완료 조건 D2) 실험이고, 안 보내면 기본값 0.6 이다.
 */
public record ScenarioSettings(
        Integer riders,
        Double ordersPerSec,
        Long locationIntervalMs,
        Double acceptRate,
        Double rejectRate,
        Long acceptDelayMs,
        Long pickupDelayMs,
        Long completeDelayMs,
        Double centerLat,
        Double centerLng,
        Double spreadKm,
        Double speedKmh,
        Long durationSec
) {

    public Resolved resolve(SimulatorProperties d) {
        return new Resolved(
                or(riders, d.riders()), or(ordersPerSec, d.ordersPerSec()),
                or(locationIntervalMs, d.locationIntervalMs()),
                or(acceptRate, d.acceptRate()), or(rejectRate, d.rejectRate()),
                or(acceptDelayMs, d.acceptDelayMs()), or(pickupDelayMs, d.pickupDelayMs()),
                or(completeDelayMs, d.completeDelayMs()),
                or(centerLat, d.centerLat()), or(centerLng, d.centerLng()), or(spreadKm, d.spreadKm()),
                or(speedKmh, d.speedKmh()), or(durationSec, d.durationSec()));
    }

    private static <T> T or(T value, T fallback) {
        return value != null ? value : fallback;
    }

    public record Resolved(
            int riders, double ordersPerSec, long locationIntervalMs,
            double acceptRate, double rejectRate, long acceptDelayMs,
            long pickupDelayMs, long completeDelayMs,
            double centerLat, double centerLng, double spreadKm,
            double speedKmh, long durationSec
    ) {

        /** 수락과 거절을 뺀 나머지가 무응답이다 */
        public double ignoreRate() {
            return Math.max(0, 1 - acceptRate - rejectRate);
        }
    }
}
