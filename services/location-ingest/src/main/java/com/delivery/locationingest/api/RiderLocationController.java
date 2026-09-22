package com.delivery.locationingest.api;

import com.delivery.locationingest.ingest.LocationIngestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/riders")
@RequiredArgsConstructor
public class RiderLocationController {

    private final LocationIngestService locationIngestService;

    /**
     * LI-01 위치 수신.
     *
     * <p>본문 없이 202 다. 받았다는 것만 알리고 발행 결과는 안 기다린다. 카프카 ack 를 기다리면
     * linger.ms 20ms 가 그대로 응답 시간에 얹혀서 p99 20ms 목표를 시작부터 넘긴다.
     * 필터에 걸려서 발행을 안 한 경우도 똑같이 202 다. 앱은 그 차이를 알 필요가 없다.
     */
    @PostMapping("/{riderId}/location")
    public ResponseEntity<Void> receive(@PathVariable long riderId, @RequestBody LocationRequest request) {
        locationIngestService.ingest(riderId, request.lat(), request.lng(), request.sentAt());
        return ResponseEntity.accepted().build();
    }
}
