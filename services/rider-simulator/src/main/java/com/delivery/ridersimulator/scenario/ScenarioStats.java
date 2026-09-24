package com.delivery.ridersimulator.scenario;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * SM-03 에 내보낼 숫자들. 라이더 스레드 수천 개가 동시에 올려서 LongAdder 를 쓴다.
 */
public final class ScenarioStats {

    final LongAdder locationsSent = new LongAdder();
    final LongAdder ordersCreated = new LongAdder();
    final LongAdder offersReceived = new LongAdder();
    final LongAdder accepted = new LongAdder();
    final LongAdder rejected = new LongAdder();
    final LongAdder ignored = new LongAdder();
    final LongAdder pickedUp = new LongAdder();
    final LongAdder delivered = new LongAdder();
    /** 이 시나리오에 없는 라이더 앞으로 온 푸시. 앞 시나리오의 라이더에게 늦게 온 제안 같은 것 */
    final LongAdder unknownRider = new LongAdder();

    /** 실패한 호출을 이유별로. 예: accept:OFFER_EXPIRED, order:UNREACHABLE */
    private final Map<String, LongAdder> failures = new ConcurrentHashMap<>();

    /**
     * 초당 좌표 수. 직전 1초 칸을 보여준다.
     *
     * <p>synchronized 한 번으로 끝낸다. 라이더 1000명이 3초마다 부르면 초당 333번이라 경합이랄 게 없다.
     */
    private long currentSecond;
    private long thisSecond;
    private long lastSecond;

    void failed(String call, String code) {
        failures.computeIfAbsent(call + ":" + code, k -> new LongAdder()).increment();
    }

    synchronized void locationSent(long nowMillis) {
        roll(nowMillis / 1000);
        thisSecond++;
        locationsSent.increment();
    }

    synchronized long locationsPerSec(long nowMillis) {
        roll(nowMillis / 1000);
        return lastSecond;
    }

    /** 초가 바뀌었으면 칸을 넘긴다. 1초 넘게 비었으면 직전 칸은 0 이다 */
    private void roll(long second) {
        if (second == currentSecond) {
            return;
        }
        lastSecond = second == currentSecond + 1 ? thisSecond : 0;
        thisSecond = 0;
        currentSecond = second;
    }

    Map<String, Long> failures() {
        Map<String, Long> out = new java.util.TreeMap<>();
        failures.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }
}
