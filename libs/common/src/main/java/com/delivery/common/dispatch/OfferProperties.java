package com.delivery.common.dispatch;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 제안 하나를 다루는 데 쓰는 시간값들. dispatch-engine 과 offer-relay 가 <b>같은 값</b>을 써야 한다.
 *
 * <p>그래서 서비스마다 yml 에 적지 않고 여기 기본값으로 박아뒀다. 양쪽 yml 에 따로 적어두면
 * 한쪽만 고쳤을 때 이런 일이 난다 — dispatch-engine 은 라이더를 12초 찜하는데 offer-relay 는
 * 8초로 찜한다. 재제안받은 라이더의 찜이 제안보다 2초 먼저 풀려서, 그 2초 사이에 들어온
 * 다른 주문이 같은 라이더를 또 잡아간다. 라이더 화면에 제안이 두 개 뜬다.
 *
 * <p>바꾸고 싶으면 {@code delivery.offer.*} 로 덮으면 되는데, <b>양쪽 서비스에 똑같이</b> 넣어야 한다.
 */
@ConfigurationProperties(prefix = "delivery.offer")
public record OfferProperties(

        /*
         * 배차 리스 유지 시간. 정상이면 100ms 안에 끝난다. 이 값은 "프로세스가 죽어도 이만큼
         * 뒤엔 알아서 풀려라" 는 안전장치지 정상 경로에서 쓰는 시간이 아니다.
         */
        @DefaultValue("15s") Duration leaseTtl,

        /*
         * 라이더 찜 유지 시간. 제안 TTL 10초에 2초를 얹었다. 먼저 풀리면 제안이 살아 있는데
         * 다른 주문이 그 라이더를 또 잡아가고, 너무 길면 거절한 라이더가 한동안 논다.
         */
        @DefaultValue("12s") Duration riderLockTtl,

        /*
         * 후보 목록과 제안 보드의 TTL. 지우는 코드가 안 도는 경로가 너무 많아서(프로세스 죽음,
         * 예외, 발행 실패) 지우는 쪽이 아니라 만료 쪽에 기댄다.
         */
        @DefaultValue("10m") Duration stateTtl
) {
}
