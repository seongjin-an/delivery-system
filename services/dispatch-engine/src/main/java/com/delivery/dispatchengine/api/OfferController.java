package com.delivery.dispatchengine.api;

import com.delivery.common.response.ApiResponse;
import com.delivery.dispatchengine.offer.OfferResponseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/offers")
@RequiredArgsConstructor
public class OfferController {

    private final OfferResponseService offerResponseService;

    /**
     * DE-04 제안 수락.
     *
     * <p>실패를 세 가지로 가른다. 409 는 이미 누가 받은 것, 410 은 만료된 것, 403 은 남의 제안이다.
     * 라이더가 다음에 할 행동이 셋 다 달라서 하나로 뭉치면 안 된다 — 410 이면 곧 다른 제안이
     * 올 수 있으니 앱을 켜둬야 하고, 403 이면 앱이 잘못 부른 거라 버그 신고감이다.
     * 상태 코드로 바꾸는 건 GlobalExceptionHandler 가 ErrorCode 를 보고 한다.
     */
    @PostMapping("/{offerId}/accept")
    public ApiResponse<OfferResultResponse> accept(
            @PathVariable long offerId,
            @Valid @RequestBody OfferResponseRequest request) {

        return ApiResponse.ok(OfferResultResponse.from(
                offerResponseService.accept(offerId, request.riderId())));
    }

    /**
     * DE-05 제안 거절.
     *
     * <p>응답이 200 인 건 "거절을 접수했다" 는 뜻이지 다음 후보를 찾았다는 뜻이 아니다.
     * 재제안은 offer-relay 가 비동기로 한다. 라이더 앱을 거기까지 기다리게 할 이유가 없고,
     * 기다리게 하면 거절 한 번에 후보 검색과 발행까지 붙어서 응답이 몇백 ms 로 늘어난다.
     *
     * <p>실패 코드는 수락과 같다. 같은 판정을 통과해야 해서다 — 이미 만료된 제안은 거절도 못 한다.
     */
    @PostMapping("/{offerId}/reject")
    public ApiResponse<OfferResultResponse> reject(
            @PathVariable long offerId,
            @Valid @RequestBody OfferResponseRequest request) {

        return ApiResponse.ok(OfferResultResponse.from(
                offerResponseService.reject(offerId, request.riderId())));
    }
}
