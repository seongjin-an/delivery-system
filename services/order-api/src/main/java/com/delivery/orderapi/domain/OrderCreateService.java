package com.delivery.orderapi.domain;

import com.delivery.common.Ids;
import com.delivery.common.exception.BusinessException;
import com.delivery.common.exception.ErrorCode;
import com.delivery.common.geo.Coordinates;
import com.delivery.common.geo.Zones;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.OptionalLong;

/**
 * OR-01 주문 생성.
 *
 * <p>순서가 중요하다. 검증 → 멱등키 선점 → DB 저장 순으로 간다.
 * 멱등키를 먼저 잡는 이유는, 중복 요청 두 개가 동시에 들어왔을 때 둘 다 좌표 검증을 통과하고
 * 나란히 INSERT 를 시도하는 걸 막으려는 것이다. 레디스 NX 가 그 문 앞에서 하나만 통과시킨다.
 *
 * <p>카프카 발행은 여기서 하지 않는다(OR-01 규칙 2번). 아웃박스에 넣기만 하고 OR-06 폴러가 보낸다.
 */
@Service
@RequiredArgsConstructor
public class OrderCreateService {

    private static final Logger log = LoggerFactory.getLogger(OrderCreateService.class);

    /** 가게와 목적지가 이보다 멀면 받지 않는다 (OR-01 예외 표) */
    private static final int MAX_DELIVERY_METERS = 20_000;

    private final IdempotencyStore idempotencyStore;
    private final OrderWriter orderWriter;
    private final OrderRepository orderRepository;

    public Result create(String idempotencyKey, NewOrder command) {
        requireIdempotencyKey(idempotencyKey);

        Coordinates.validate(command.storeLat(), command.storeLng());
        Coordinates.validate(command.destLat(), command.destLng());

        int distanceMeters = (int) Math.round(Coordinates.distanceMeters(
                command.storeLat(), command.storeLng(), command.destLat(), command.destLng()));
        if (distanceMeters > MAX_DELIVERY_METERS) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "가게와 목적지가 너무 멀어요 (%dm, 최대 %dm)".formatted(distanceMeters, MAX_DELIVERY_METERS));
        }

        long orderId = Ids.newId();
        if (!idempotencyStore.reserve(idempotencyKey, orderId)) {
            return replay(idempotencyKey, command);
        }

        Instant now = Instant.now();
        Order order = Order.create(orderId, command.storeId(),
                command.storeLat(), command.storeLng(), command.destLat(), command.destLng(),
                Zones.of(command.storeLat(), command.storeLng()),
                command.priceKrw(), distanceMeters, now);

        try {
            return Result.created(orderWriter.write(order));
        } catch (RuntimeException e) {
            // 잡아둔 키를 놓아주지 않으면, DB 가 잠깐 흔들려서 실패한 주문의 멱등키가 한 시간 동안
            // 남는다. 손님이 다시 눌러도 "이미 처리됨" 으로 막히는데 정작 주문은 어디에도 없다.
            idempotencyStore.release(idempotencyKey);
            throw e;
        }
    }

    /**
     * 이미 쓴 멱등키로 다시 들어온 요청. 새 주문을 만들지 않고 앞서 만든 주문을 그대로 돌려준다.
     *
     * <p>DB 에서 못 찾는 경우가 있는데, 앞 요청이 키만 잡고 아직 커밋을 안 끝낸 짧은 순간이다.
     * 그때는 잡혀 있는 orderId 와 CREATED 를 그대로 알려준다 — 곧 그 상태가 되니까 거짓말은 아니다.
     * 여기서 404 를 주면 앱이 "주문이 없네" 하고 또 새로 만들어서 중복 주문이 생긴다.
     */
    private Result replay(String idempotencyKey, NewOrder command) {
        OptionalLong reserved = idempotencyStore.findOrderId(idempotencyKey);
        if (reserved.isEmpty()) {
            // 키를 잡는 데는 실패했는데 읽으니 없다. TTL 이 그 사이에 끝난 아주 드문 경우다.
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "멱등키 처리 중 문제가 생겼어요. 다시 시도해 주세요");
        }

        long existingOrderId = reserved.getAsLong();
        return orderRepository.findById(existingOrderId)
                .map(Result::replayed)
                .orElseGet(() -> {
                    log.info("멱등키 재사용인데 주문이 아직 커밋 전이다: orderId={}", existingOrderId);
                    return Result.inFlight(existingOrderId,
                            Zones.of(command.storeLat(), command.storeLng()));
                });
    }

    private static void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BusinessException(ErrorCode.MISSING_IDEMPOTENCY_KEY);
        }
    }

    /** 컨트롤러가 받는 입력. 웹 DTO 를 도메인까지 끌고 들어오지 않으려고 한 겹 둔다 */
    public record NewOrder(
            String storeId,
            double storeLat,
            double storeLng,
            double destLat,
            double destLng,
            int priceKrw
    ) {
    }

    /**
     * 새로 만든 건지(201) 이미 있던 건지(200) 를 컨트롤러가 알아야 해서 같이 돌려준다.
     */
    public record Result(long orderId, OrderStatus status, String zoneId, boolean isNew) {

        static Result created(Order order) {
            return new Result(order.getOrderId(), order.getStatus(), order.getZoneId(), true);
        }

        static Result replayed(Order order) {
            return new Result(order.getOrderId(), order.getStatus(), order.getZoneId(), false);
        }

        static Result inFlight(long orderId, String zoneId) {
            return new Result(orderId, OrderStatus.CREATED, zoneId, false);
        }
    }
}
