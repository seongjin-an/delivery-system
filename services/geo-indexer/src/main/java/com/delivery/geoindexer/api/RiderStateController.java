package com.delivery.geoindexer.api;

import com.delivery.common.response.ApiResponse;
import com.delivery.geoindexer.geo.RiderStateReader;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/riders")
@RequiredArgsConstructor
public class RiderStateController {

    private final RiderStateReader riderStateReader;

    /**
     * GI-03 디버깅용 라이더 상태. 없는 라이더도 404 가 아니라 200 에 blockers 로 답한다.
     * "없다" 자체가 배차가 안 되는 이유라서, 그걸 에러로 돌려주면 보는 사람이 한 번 더 헤맨다.
     */
    @GetMapping("/{riderId}/state")
    public ApiResponse<RiderStateReader.RiderSnapshot> state(@PathVariable long riderId) {
        return ApiResponse.ok(riderStateReader.read(riderId));
    }
}
