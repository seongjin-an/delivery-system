package com.delivery.ridersimulator.scenario;

import com.delivery.common.Ids;
import com.delivery.common.event.OfferPush;
import com.delivery.common.exception.BusinessException;
import com.delivery.common.exception.ErrorCode;
import com.delivery.ridersimulator.config.SimulatorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 시뮬레이션 한 판. SM-01 시작, SM-02 정지, SM-03 상태, SM-04 제안 처리.
 *
 * <p>스레드가 세 종류다. 라이더마다 좌표를 보내는 루프 하나, 주문을 만드는 루프 하나, 그리고 제안을
 * 받을 때마다 수락 → 픽업 → 완료를 이어서 부르는 짧은 작업 하나씩. 전부 가상 스레드라 라이더 1천 명이면
 * 스레드도 1천 개가 넘지만 그냥 돈다 (기능 정의서 SM-01 규칙 1번).
 *
 * <p>정지할 때 앞의 두 종류만 끊는다. 이미 수락한 배달까지 끊으면 그 라이더는 DELIVERING 으로 영영 남는다.
 * GI-02 도 배달 중인 라이더는 안 치운다. 그래서 수락한 건 픽업과 완료까지 마저 부른다 (최대 35초).
 */
@Slf4j
@Component
public class Scenario {

    private static final double METERS_PER_DEG_LAT = 111_320;

    private final SimulatorClient client;
    private final SimulatorProperties defaults;

    /** 배달 작업은 판이 바뀌어도 끝까지 가야 해서 판과 따로 둔다 */
    private final ExecutorService deliveries = Executors.newVirtualThreadPerTaskExecutor();

    private volatile Run current;

    public Scenario(SimulatorClient client, SimulatorProperties defaults) {
        this.client = client;
        this.defaults = defaults;
    }

    /** 한 판의 상태. 정지하면 이 객체는 버리고, 다음 판은 새 라이더로 시작한다 */
    private static final class Run {
        final ScenarioSettings.Resolved settings;
        final Map<Long, SimulatedRider> riders = new ConcurrentHashMap<>();
        final Map<Long, Instant> lastSeen = new ConcurrentHashMap<>();
        final List<Thread> loops = new ArrayList<>();
        final ScenarioStats stats = new ScenarioStats();
        final Instant startedAt = Instant.now();
        volatile boolean running = true;
        volatile Instant stoppedAt;

        Run(ScenarioSettings.Resolved settings) {
            this.settings = settings;
        }
    }

    public synchronized ScenarioStatus start(ScenarioSettings request) {
        if (current != null && current.running) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "이미 돌고 있어요. 먼저 /sim/stop 을 부르세요");
        }
        ScenarioSettings.Resolved settings = request.resolve(defaults);
        validate(settings);

        Run run = new Run(settings);
        for (int i = 0; i < settings.riders(); i++) {
            SimulatedRider rider = new SimulatedRider(Ids.newId(),
                    settings.centerLat(), settings.centerLng(), settings.spreadKm());
            run.riders.put(rider.riderId(), rider);
            run.loops.add(Thread.ofVirtual().name("sim-rider-", i).start(() -> riderLoop(run, rider)));
        }
        if (settings.ordersPerSec() > 0) {
            run.loops.add(Thread.ofVirtual().name("sim-orders").start(() -> orderLoop(run)));
        }
        if (settings.durationSec() > 0) {
            run.loops.add(Thread.ofVirtual().name("sim-timer").start(() -> {
                sleepQuietly(Duration.ofSeconds(settings.durationSec()));
                stop(run);
            }));
        }
        current = run;
        log.info("시뮬레이션 시작: 라이더 {}명, 주문 초당 {}건, 수락 {} 거절 {} 무응답 {}",
                settings.riders(), settings.ordersPerSec(), settings.acceptRate(), settings.rejectRate(),
                settings.ignoreRate());
        return status();
    }

    public synchronized ScenarioStatus stop() {
        if (current != null) {
            stop(current);
        }
        return status();
    }

    private void stop(Run run) {
        if (!run.running) {
            return;
        }
        run.running = false;
        run.stoppedAt = Instant.now();
        run.loops.forEach(Thread::interrupt);
        log.info("시뮬레이션 정지. 이미 수락한 배달은 완료까지 마저 부른다");
    }

    /** 테스트에서 이 판의 라이더 아이디를 꺼낼 때 쓴다 */
    Set<Long> riderIds() {
        Run run = current;
        return run == null ? Set.of() : run.riders.keySet();
    }

    public ScenarioStatus status() {
        Run run = current;
        if (run == null) {
            return ScenarioStatus.idle();
        }
        long now = System.currentTimeMillis();
        Instant end = run.stoppedAt != null ? run.stoppedAt : Instant.now();
        // 30초 안에 좌표가 받아들여진 라이더. GI-02 가 오프라인으로 보는 기준과 같다
        Instant cutoff = Instant.now().minusSeconds(30);
        long online = run.lastSeen.values().stream().filter(t -> t.isAfter(cutoff)).count();
        ScenarioStats s = run.stats;
        return new ScenarioStatus(run.running, Duration.between(run.startedAt, end).toSeconds(),
                run.riders.size(), online, s.locationsPerSec(now), s.locationsSent.sum(),
                s.ordersCreated.sum(), s.offersReceived.sum(),
                s.accepted.sum(), s.rejected.sum(), s.ignored.sum(),
                s.pickedUp.sum(), s.delivered.sum(), s.unknownRider.sum(), s.failures());
    }

    /**
     * SM-04 제안 수신. 여기서는 판단만 하고 곧바로 돌아간다.
     *
     * <p>수락 대기(acceptDelayMs, 기본 2초)를 웹훅 안에서 기다리면 안 된다. notification-worker 가 웹훅 응답을 2초까지만
     * 기다려서, 그걸 넘기면 발송 실패로 보고 같은 제안을 또 보낸다. 라이더가 한 제안에 두 번 수락을 누르는 꼴이 된다.
     */
    public void onOffer(OfferPush push) {
        Run run = current;
        SimulatedRider rider = run == null ? null : run.riders.get(push.riderId());
        if (rider == null) {
            if (run != null) {
                run.stats.unknownRider.increment();
            }
            return;
        }
        run.stats.offersReceived.increment();
        if (!run.running) {
            // 멈춘 뒤에도 30~40초 동안은 제안이 온다. GI-02 가 치우기 전까지 레디스에선 이 라이더들이 아직 IDLE 이라서다.
            // 여기서 받아버리면 정지한 판에서 새 배달이 시작된다. 앱을 끈 라이더처럼 응답하지 않는다.
            run.stats.ignored.increment();
            return;
        }

        ScenarioSettings.Resolved settings = run.settings;
        double dice = ThreadLocalRandom.current().nextDouble();
        if (dice < settings.acceptRate()) {
            run.stats.accepted.increment();
            deliveries.submit(() -> acceptAndDeliver(run, push));
        } else if (dice < settings.acceptRate() + settings.rejectRate()) {
            run.stats.rejected.increment();
            deliveries.submit(() -> record(run, "reject", client.reject(push.offerId(), push.riderId())));
        } else {
            // 무응답. 아무것도 안 한다. 10초 뒤 타이머가 만료시켜서 다음 후보로 간다
            run.stats.ignored.increment();
        }
    }

    /** 기능 정의서 SM-04 규칙 2번. 수락하면 픽업과 완료까지 이어서 부른다 */
    private void acceptAndDeliver(Run run, OfferPush push) {
        ScenarioSettings.Resolved settings = run.settings;
        sleepQuietly(Duration.ofMillis(settings.acceptDelayMs()));
        if (!record(run, "accept", client.accept(push.offerId(), push.riderId()))) {
            return;
        }
        sleepQuietly(Duration.ofMillis(settings.pickupDelayMs()));
        if (!record(run, "pickup", client.pickUp(push.orderId(), push.riderId()))) {
            return;
        }
        run.stats.pickedUp.increment();
        sleepQuietly(Duration.ofMillis(settings.completeDelayMs()));
        if (record(run, "complete", client.complete(push.orderId(), push.riderId()))) {
            run.stats.delivered.increment();
        }
    }

    private void riderLoop(Run run, SimulatedRider rider) {
        ScenarioSettings.Resolved settings = run.settings;
        double stepMeters = settings.speedKmh() * 1000 / 3600 * settings.locationIntervalMs() / 1000;
        // 1천 명이 같은 밀리초에 몰리지 않게 첫 전송을 주기 안에서 흩어놓는다
        sleepQuietly(Duration.ofMillis(ThreadLocalRandom.current().nextLong(Math.max(1, settings.locationIntervalMs()))));
        while (run.running && !Thread.currentThread().isInterrupted()) {
            rider.walk(stepMeters);
            if (record(run, "location", client.sendLocation(rider))) {
                run.lastSeen.put(rider.riderId(), Instant.now());
                run.stats.locationSent(System.currentTimeMillis());
            }
            if (!sleepQuietly(Duration.ofMillis(settings.locationIntervalMs()))) {
                return;
            }
        }
    }

    /**
     * 주문을 초당 ordersPerSec 건 만든다. 기능 정의서 SM-01 규칙 3번.
     *
     * <p>목적지는 가게에서 0.5~3km 안이다. 배차 검색 반경이 3km 고 주문 거리 상한이 20km 라서,
     * 목적지를 판 전체에서 뽑으면 8km 판에서 16km 짜리 주문이 섞여 정산이 비현실적으로 나온다.
     */
    private void orderLoop(Run run) {
        ScenarioSettings.Resolved settings = run.settings;
        long intervalNanos = (long) (1_000_000_000L / settings.ordersPerSec());
        long next = System.nanoTime();
        while (run.running && !Thread.currentThread().isInterrupted()) {
            double[] store = randomPoint(settings.centerLat(), settings.centerLng(), settings.spreadKm() * 1000, 0);
            double[] dest = randomPoint(store[0], store[1], 3000, 500);
            // 주문 하나 넣는 데 걸리는 시간 때문에 초당 건수가 밀리지 않게 따로 띄운다
            deliveries.submit(() -> {
                if (record(run, "order", client.createOrder(store[0], store[1], dest[0], dest[1]))) {
                    run.stats.ordersCreated.increment();
                }
            });
            next += intervalNanos;
            long waitNanos = next - System.nanoTime();
            if (waitNanos > 0 && !sleepQuietly(Duration.ofNanos(waitNanos))) {
                return;
            }
        }
    }

    /** 성공이면 true. 실패는 이유별로 센다 */
    private static boolean record(Run run, String call, String code) {
        if (SimulatorClient.OK.equals(code)) {
            return true;
        }
        run.stats.failed(call, code);
        return false;
    }

    private static double[] randomPoint(double lat, double lng, double maxMeters, double minMeters) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double r = Math.sqrt(random.nextDouble(minMeters * minMeters / (maxMeters * maxMeters), 1)) * maxMeters;
        double theta = random.nextDouble(2 * Math.PI);
        return new double[]{
                lat + r * Math.cos(theta) / METERS_PER_DEG_LAT,
                lng + r * Math.sin(theta) / (METERS_PER_DEG_LAT * Math.cos(Math.toRadians(lat)))};
    }

    private static void validate(ScenarioSettings.Resolved s) {
        if (s.riders() < 1 || s.riders() > 20_000) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "라이더는 1명에서 2만 명 사이로 주세요");
        }
        if (s.acceptRate() < 0 || s.rejectRate() < 0 || s.acceptRate() + s.rejectRate() > 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "acceptRate 와 rejectRate 는 0 이상이고 합이 1 을 넘으면 안 돼요");
        }
        if (s.ordersPerSec() < 0 || s.locationIntervalMs() < 100 || s.spreadKm() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "ordersPerSec 는 0 이상, locationIntervalMs 는 100 이상, spreadKm 는 0 보다 커야 해요");
        }
    }

    /** @return 끝까지 잤으면 true, 끊겼으면 false */
    private static boolean sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
