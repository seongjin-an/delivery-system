package com.delivery.locationingest.ingest;

import com.delivery.common.event.RiderLocation;

/**
 * 좌표를 내보내는 곳. 2단계 실험에서 카프카와 래빗엠큐를 갈아끼우려고 뽑아낸 것이다.
 *
 * <p>어느 쪽이든 결과를 안 기다리고, 실패하면 {@code onFailure} 로 이동거리 필터에 알린다.
 */
public interface LocationSink {

    void publish(RiderLocation location, Runnable onFailure);
}
