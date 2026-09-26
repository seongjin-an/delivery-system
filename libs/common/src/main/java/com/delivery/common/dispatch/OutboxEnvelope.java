package com.delivery.common.dispatch;

import com.delivery.common.JsonUtil;

/**
 * 레디스 아웃박스에 넣는 이벤트 한 건. 카프카로 보낼 때 필요한 걸 다 들고 있다.
 *
 * @param enqueuedAt 넣은 시각(epoch ms). 보내다 죽어서 inflight 에 남은 걸 다시 보낼지 이걸로 정한다
 */
public record OutboxEnvelope(String topic, String key, String payload, long enqueuedAt) {

    public String toJson() {
        return JsonUtil.toJson(this);
    }

    public static OutboxEnvelope fromJson(String json) {
        return JsonUtil.fromJson(json, OutboxEnvelope.class);
    }
}
