package com.delivery.dispatchengine.api;

import com.delivery.common.response.ApiResponse;
import com.delivery.dispatchengine.offer.OfferAcceptService;
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

    private final OfferAcceptService offerAcceptService;

    /**
     * DE-04 제안 수락.
     *
     * <p>실패를 네 가지로 가른다. 409 는 이미 누가 받은 것, 410 은 만료된 것, 403 은 남의 제안이다.
     * 라이더가 다음에 할 행동이 셋 다 달라서 하나로 뭉치면 안 된다 — 410 이면 곧 다른 제안이
     * 올 수 있으니 앱을 켜둬야 하고, 403 이면 앱이 잘못 부른 거라 버그 신고감이다.
     * 상태 코드로 바꾸는 건 GlobalExceptionHandler 가 ErrorCode 를 보고 한다.
     */
    @PostMapping("/{offerId}/accept")
    public ApiResponse<AcceptOfferResponse> accept(
            @PathVariable long offerId,
            @Valid @RequestBody AcceptOfferRequest request) {

        return ApiResponse.ok(AcceptOfferResponse.from(
                offerAcceptService.accept(offerId, request.riderId())));
    }
}
