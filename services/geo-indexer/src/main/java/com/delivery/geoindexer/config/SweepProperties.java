package com.delivery.geoindexer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * GI-02 오프라인 정리 손잡이들.
 *
 * <p>주기(interval)와 첫 실행 지연(initial-delay)은 여기 없다. @Scheduled 가 프로퍼티 문자열을
 * 직접 읽어서, application.yml 의 delivery.sweep 아래에 같이 적어만 뒀다.
 */
@ConfigurationProperties(prefix = "delivery.sweep")
public record SweepProperties(

        /* 좌표가 이만큼 안 오면 오프라인으로 본다. 라이더 앱이 3초마다 보내니 10번을 연달아 놓친 거다 */
        Duration offlineAfter,

        /*
         * 한 번에 한 대만 돌게 잡는 락의 유지 시간. 주기(10초)보다 짧아야 한다.
         * 같거나 길면, 락을 잡은 인스턴스가 죽었을 때 다음 주기에 아무도 못 잡고 한 번을 통째로 건너뛴다.
         */
        Duration lockTtl,

        /* ZRANGEBYSCORE 한 번에 가져올 라이더 수. 이만큼씩 파이프라인 한 번으로 처리한다 */
        int batchSize,

        /*
         * 한 번 돌 때 이 시간을 넘기면 남은 건 다음 주기로 넘긴다. 락 유지 시간보다 짧아야 한다.
         * 넘기면 락이 풀린 채로 계속 돌아서 다른 인스턴스와 겹친다. 겹쳐도 Lua 가 다시 판정해서
         * 틀린 결과는 안 나오지만, 같은 일을 두 번 하게 된다.
         */
        Duration timeBudget
) {
}
