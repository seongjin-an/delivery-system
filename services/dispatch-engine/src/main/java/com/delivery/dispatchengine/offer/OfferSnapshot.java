package com.delivery.dispatchengine.offer;

import com.delivery.common.dispatch.OfferState;

/** {@code dispatch:offer:{orderId}} 를 읽은 결과. 키가 없으면 null 을 쓴다 */
public record OfferSnapshot(long offerId, long riderId, OfferState state, int attempt, long offeredAt) {
}
