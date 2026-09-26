package com.delivery.dispatchengine.api;

import com.delivery.common.Times;
import com.delivery.common.response.ApiResponse;
import com.delivery.dispatchengine.candidate.Candidate;
import com.delivery.dispatchengine.candidate.CandidateFinder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 2단계 실험 전용. 주문 없이 후보 검색(DE-02)만 부른다.
 *
 * <p>주문을 초당 200건 흘리면 order-api 의 쓰기, Debezium, 카프카까지 전부 섞여서 뭘 재는지 흐려진다.
 * 여기서 CandidateFinder 를 그대로 불러서 검색 하나만 떼어 잰다. 찜은 안 한다.
 */
@RestController
@RequiredArgsConstructor
public class CandidateProbeController {

    private final CandidateFinder candidateFinder;

    public record ProbeResponse(int found, List<Candidate> candidates) {
    }

    @GetMapping("/api/candidates")
    public ApiResponse<ProbeResponse> probe(@RequestParam double lat, @RequestParam double lng) {
        List<Candidate> found = candidateFinder.find(lat, lng, Times.now().toEpochMilli());
        return ApiResponse.ok(new ProbeResponse(found.size(), found));
    }
}
