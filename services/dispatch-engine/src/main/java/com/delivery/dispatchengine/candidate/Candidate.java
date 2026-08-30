package com.delivery.dispatchengine.candidate;

/**
 * 후보 라이더 한 명. 점수는 <b>낮을수록 우선</b>이다.
 *
 * @param riderId      라이더
 * @param distanceKm   가게에서 라이더까지 직선거리
 * @param waitMinutes  IDLE 로 기다린 시간(분)
 * @param score        {@code 거리km - 대기보너스}
 */
public record Candidate(long riderId, double distanceKm, long waitMinutes, double score) {
}
