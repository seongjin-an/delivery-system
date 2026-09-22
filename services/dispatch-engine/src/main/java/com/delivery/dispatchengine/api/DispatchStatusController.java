package com.delivery.dispatchengine.api;

import com.delivery.common.RedisKeys;
import com.delivery.common.dispatch.CandidateList;
import com.delivery.common.dispatch.OfferBoard;
import com.delivery.common.dispatch.OfferFields;
import com.delivery.common.dispatch.RiderState;
import com.delivery.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DE-06 배차 상태 조회. <b>디버깅 전용이고 고객에게 노출하지 않는다.</b>
 *
 * <p>레디스 값을 가공 없이 그대로 내보낸다. 보기 좋게 다듬으면 "코드가 이렇게 읽었다" 를 보는
 * 도구가 아니라 "코드가 이렇게 보여주고 싶어한다" 를 보는 도구가 된다. 배차가 이상할 때 정작
 * 알고 싶은 건 날것의 값이다 — {@code state} 에 오타난 문자열이 들어가 있다든가 하는 것.
 */
@RestController
@RequestMapping("/api/dispatch")
@RequiredArgsConstructor
public class DispatchStatusController {

    private final OfferBoard offerBoard;
    private final CandidateList candidateList;
    private final RiderState riderState;
    private final StringRedisTemplate redis;

    @GetMapping("/{orderId}")
    public ApiResponse<DispatchStatusResponse> status(@PathVariable long orderId) {
        Map<String, String> offer = offerBoard.dump(orderId);

        // 제안이 나가 있으면 그 라이더 상태도 같이 본다. 배차 사고의 절반은 "후보로 뽑혔는데
        // 상태가 IDLE 이 아니었다" 라서, 두 개를 따로 조회하면 그 사이에 값이 바뀐다.
        Map<String, String> rider = Map.of();
        String riderId = offer.get(OfferFields.RIDER_ID);
        if (riderId != null) {
            rider = new LinkedHashMap<>();
            for (Map.Entry<Object, Object> entry : riderState.dump(Long.parseLong(riderId)).entrySet()) {
                rider.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }

        return ApiResponse.ok(new DispatchStatusResponse(
                orderId,
                offer,
                candidateList.remaining(orderId),
                redis.opsForValue().get(RedisKeys.dispatchLock(orderId)),
                rider));
    }
}
