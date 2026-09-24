package com.delivery.ridersimulator.api;

import com.delivery.common.event.OfferPush;
import com.delivery.common.response.ApiResponse;
import com.delivery.ridersimulator.scenario.Scenario;
import com.delivery.ridersimulator.scenario.ScenarioSettings;
import com.delivery.ridersimulator.scenario.ScenarioStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시뮬레이터 손잡이. 프론트가 없으니 이 네 개가 전부다.
 *
 * <p>다른 서비스처럼 ApiResponse 로 감싼다. 기능 정의서 SM-03 예시는 감싸지 않은 모양인데,
 * 에러가 났을 때 GlobalExceptionHandler 가 주는 모양과 성공 모양이 다르면 부르는 쪽이 두 가지로 읽어야 한다.
 */
@RestController
@RequestMapping("/sim")
@RequiredArgsConstructor
public class SimulatorController {

    private final Scenario scenario;

    /** SM-01. 본문을 비워 보내면 application.yml 기본값으로 돈다 */
    @PostMapping("/start")
    public ApiResponse<ScenarioStatus> start(@RequestBody(required = false) ScenarioSettings settings) {
        return ApiResponse.ok(scenario.start(settings != null ? settings : emptySettings()));
    }

    /** SM-02. 새 주문과 좌표만 멈춘다. 이미 수락한 배달은 완료까지 마저 부른다 */
    @PostMapping("/stop")
    public ApiResponse<ScenarioStatus> stop() {
        return ApiResponse.ok(scenario.stop());
    }

    /** SM-03 */
    @GetMapping("/status")
    public ApiResponse<ScenarioStatus> status() {
        return ApiResponse.ok(scenario.status());
    }

    /**
     * SM-04 가짜 푸시 웹훅. notification-worker(NW-02)가 부른다.
     *
     * <p>본문 없이 200 만 준다. 판단은 여기서 하고 수락 대기는 다른 스레드에서 한다 (Scenario.onOffer 주석).
     */
    @PostMapping("/push")
    public ResponseEntity<Void> push(@RequestBody OfferPush push) {
        scenario.onOffer(push);
        return ResponseEntity.ok().build();
    }

    private static ScenarioSettings emptySettings() {
        return new ScenarioSettings(null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
