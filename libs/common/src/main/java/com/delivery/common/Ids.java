package com.delivery.common;

import com.github.f4b6a3.tsid.TsidCreator;

/**
 * orderId, riderId, offerId 를 만드는 자리. 기능 정의서 3.1.
 *
 * <p>TSID 를 쓴다. 64비트라 DB 에는 {@code BIGINT} 로 들어가고, 앞쪽 42비트가 밀리초 타임스탬프라
 * 숫자 크기가 곧 만들어진 순서다. 로그에서 주문 번호만 보고도 어느 게 먼저 들어온 건지 알 수 있다.
 *
 * <p>왜 DB 의 AUTO_INCREMENT 가 아니냐면, 이 아이디가 카프카 파티션 키로, 레디스 키로,
 * 래빗엠큐 메시지 안으로 그대로 흘러다녀서다. DB 가 번호를 매겨줄 때까지 기다려야 하면
 * 그 앞 단계에서 아이디를 쓸 수가 없다. 게다가 1씩 늘어나는 주문번호는 아침저녁으로 한 번씩
 * 주문해보면 하루 주문량이 그대로 새어 나간다.
 *
 * <p><b>인스턴스를 여러 대 띄울 때 주의.</b> 같은 밀리초에 두 인스턴스가 같은 번호를 만들지
 * 않으려면 각자 다른 노드 번호를 들고 있어야 한다. {@code -Dtsidcreator.node=N} (또는 환경변수
 * {@code TSIDCREATOR_NODE}) 로 준다. 안 주면 라이브러리가 무작위로 고르는데, 인스턴스를 늘렸다
 * 줄였다 하는 이 프로젝트에서는 언젠가 겹친다. scripts/_common.sh 가 포트에서 뽑아 넣어준다.
 */
public final class Ids {

    /**
     * 노드 10비트(1024대) + 밀리초당 12비트(4096개) 짜리를 쓴다.
     * 우리는 인스턴스가 많아야 스무 대 남짓이라 노드를 더 늘릴 이유가 없고,
     * 밀리초당 4096개면 초당 400만 건이라 목표치(초당 200 주문)와는 거리가 멀다.
     */
    public static long newId() {
        return TsidCreator.getTsid1024().toLong();
    }

    private Ids() {
    }
}
