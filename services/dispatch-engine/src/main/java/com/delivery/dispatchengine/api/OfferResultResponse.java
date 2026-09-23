package com.delivery.dispatchengine.api;

import com.delivery.dispatchengine.offer.OfferResponseService;

/**
 * DE-04 수락 / DE-05 거절 응답.
 *
 * <p>orderId 를 돌려주는 게 중요하다. 라이더 앱은 제안 알림에서 offerId 만 들고 왔는데,
 * 이 다음에 부를 픽업(OR-03)과 완료(OR-04)는 전부 orderId 로 부른다.
 */
public record OfferResultResponse(
        long orderId,
        long riderId,
        long offerId,
        int attempt
) {

    public static OfferResultResponse from(OfferResponseService.Assignment assignment) {
        return new OfferResultResponse(
                assignment.orderId(), assignment.riderId(),
                assignment.offerId(), assignment.attempt());
    }

    public static OfferResultResponse from(OfferResponseService.Rejection rejection) {
        return new OfferResultResponse(
                rejection.orderId(), rejection.riderId(),
                rejection.offerId(), rejection.attempt());
    }
}
