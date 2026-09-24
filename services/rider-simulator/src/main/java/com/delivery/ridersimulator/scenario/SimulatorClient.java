package com.delivery.ridersimulator.scenario;

import com.delivery.common.JsonUtil;
import com.delivery.common.Times;
import com.delivery.common.web.CommonHeaders;
import com.delivery.ridersimulator.config.SimulatorProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 시뮬레이터가 부르는 바깥 API 전부. 라이더 앱과 손님 앱이 하는 일을 대신한다.
 *
 * <p>실패해도 예외를 안 올린다. 응답의 {@code code}(ALREADY_TAKEN, OFFER_EXPIRED 같은 것)를 돌려주고
 * 부르는 쪽이 세기만 한다. 부하를 거는 도구가 409 한 번에 루프가 끊기면 실험이 그 자리에서 멈춘다.
 */
@Component
public class SimulatorClient {

    /** 응답이 2xx 면 이 값이다 */
    public static final String OK = "OK";
    /** 연결이 안 되거나 시간이 넘으면 이 값이다 */
    public static final String UNREACHABLE = "UNREACHABLE";

    private final SimulatorProperties properties;
    private final RestClient restClient;

    public SimulatorClient(SimulatorProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.restClient = builder.requestFactory(factory).build();
    }

    public String sendLocation(SimulatedRider rider) {
        return post(properties.ingestUrl() + "/api/riders/" + rider.riderId() + "/location", null,
                Map.of("lat", rider.lat(), "lng", rider.lng(), "sentAt", Times.now().toString()));
    }

    /**
     * 주문 하나. 멱등키는 요청마다 새로 만든다 (기능 정의서 SM-01 규칙 4번).
     * 같은 키를 쓰면 두 번째부터는 새 주문이 아니라 첫 주문을 돌려받는다.
     */
    public String createOrder(double storeLat, double storeLng, double destLat, double destLng) {
        return post(properties.orderUrl() + "/api/orders", UUID.randomUUID().toString(), Map.of(
                "storeId", "sim-store",
                "storeLat", storeLat, "storeLng", storeLng,
                "destLat", destLat, "destLng", destLng,
                "priceKrw", 18000));
    }

    public String accept(long offerId, long riderId) {
        return post(properties.dispatchUrl() + "/api/offers/" + offerId + "/accept", null, Map.of("riderId", riderId));
    }

    public String reject(long offerId, long riderId) {
        return post(properties.dispatchUrl() + "/api/offers/" + offerId + "/reject", null, Map.of("riderId", riderId));
    }

    public String pickUp(long orderId, long riderId) {
        return post(properties.orderUrl() + "/api/orders/" + orderId + "/pickup", null, Map.of("riderId", riderId));
    }

    public String complete(long orderId, long riderId) {
        return post(properties.orderUrl() + "/api/orders/" + orderId + "/complete", null, Map.of("riderId", riderId));
    }

    private String post(String url, String idempotencyKey, Map<String, ?> body) {
        try {
            RestClient.RequestBodySpec request = restClient.post().uri(url).contentType(MediaType.APPLICATION_JSON);
            if (idempotencyKey != null) {
                request.header(CommonHeaders.IDEMPOTENCY_KEY, idempotencyKey);
            }
            request.body(body).retrieve().toBodilessEntity();
            return OK;
        } catch (RestClientResponseException e) {
            return errorCode(e);
        } catch (Exception e) {
            return UNREACHABLE;
        }
    }

    /** ApiResponse 의 code 를 꺼낸다. 사람이 읽는 message 로는 ALREADY_TAKEN 과 OFFER_EXPIRED 를 못 가른다 */
    private static String errorCode(RestClientResponseException e) {
        try {
            JsonNode code = JsonUtil.mapper().readTree(e.getResponseBodyAsString()).get("code");
            if (code != null && !code.isNull()) {
                return code.asText();
            }
        } catch (Exception ignored) {
            // 본문이 JSON 이 아니면 상태 코드로 대신한다
        }
        return "HTTP_" + e.getStatusCode().value();
    }
}
