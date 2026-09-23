package com.delivery.notificationworker.push;

import com.delivery.common.event.OfferPush;
import com.delivery.common.event.PushMessage;
import com.delivery.notificationworker.config.PushProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 가짜 외부 푸시 API. 기능 정의서 NW-02 규칙 3, 4, 5번.
 *
 * <p>진짜 FCM 이나 APNs 를 붙이는 대신 지연(fake-latency-ms)과 실패(fail-rate)를 흉내 낸다.
 * 시나리오 C 에서 보려는 게 "외부가 느리면 워커를 늘려도 소용없다" 라서, 외부가 어떻게 느린지를
 * 손으로 돌릴 수 있어야 한다.
 *
 * <p>제안은 끝에 rider-simulator 로 POST 한다. 이게 프론트 없이 "제안 발송 → 라이더 수락" 루프를 닫는다.
 * 마케팅은 POST 하지 않는다. 시뮬레이터가 받을 게 없고, 지연과 한도는 똑같이 먹으니까 우선순위 실험에는 충분하다.
 */
@Component
public class PushSender {

    private final PushProperties properties;
    private final RestClient restClient;

    public PushSender(PushProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        // 시뮬레이터가 멈춰 있으면 기본 설정으로는 한참 붙잡힌다. 제안은 10초짜리라 2초 넘게 기다릴 이유가 없다.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.restClient = builder.requestFactory(factory).build();
    }

    /**
     * 한 번 보낸다. 실패하면 PushFailedException.
     *
     * @param expiresInSec 라이더한테 남은 시간. 웹훅 본문에 그대로 실린다
     */
    public void send(PushMessage message, long expiresInSec) throws InterruptedException {
        if (properties.fakeLatencyMs() > 0) {
            Thread.sleep(properties.fakeLatencyMs());
        }
        if (ThreadLocalRandom.current().nextDouble() < properties.failRate()) {
            throw new PushFailedException("가짜 외부 API 실패 (fail-rate=" + properties.failRate() + ")");
        }
        if (message.kind() != PushMessage.Kind.OFFER || !StringUtils.hasText(properties.webhookUrl())) {
            return;
        }
        try {
            restClient.post()
                    .uri(properties.webhookUrl())
                    .body(new OfferPush(message.riderId(), message.offerId(), message.orderId(), null, expiresInSec))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new PushFailedException("웹훅 POST 실패: " + e.getMessage(), e);
        }
    }
}
