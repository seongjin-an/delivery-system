package com.delivery.common;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 시각을 만드는 자리. 기능 정의서 3.2.
 *
 * <p>{@code Instant.now()} 를 그냥 쓰면 안 된다. 자바 9부터는 OS 가 주는 대로 마이크로초까지
 * 받아와서 {@code 04:12:33.482110Z} 같은 값이 나온다. 그런데 우리는 같은 시각을 세 군데에 서로
 * 다른 모양으로 넣는다 — MySQL 은 {@code datetime(6)}, 레디스 해시는 epoch 밀리초 정수,
 * JSON 은 ISO-8601 문자열이다.
 *
 * <p>레디스에 넣을 때 밀리초로 잘리니까, 나중에 "DB 의 createdAt 과 레디스의 offeredAt 이
 * 몇 초 차이냐" 를 볼 때 두 값의 정밀도가 달라서 미묘하게 어긋난다. 애초에 만들 때부터
 * 밀리초로 잘라두면 어디로 흘러가든 같은 값이다.
 */
public final class Times {

    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private Times() {
    }
}
