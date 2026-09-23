package com.delivery.notificationworker.api;

import com.delivery.common.RabbitTopology;
import com.delivery.common.event.PushMessage;
import com.delivery.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * NW-03 마케팅 푸시 투입. 우선순위 큐 검증용이다.
 *
 * <p>마케팅 1만 건을 priority 1 로 넣은 직후 배차 제안(priority 9)을 보내면, 제안이 1만 건을 앞질러
 * 먼저 나가야 한다. 안 그러면 점심 피크에 마케팅 한 번 쐈다고 배차가 몇 분씩 밀린다.
 */
@RestController
@RequestMapping("/api/notify")
@RequiredArgsConstructor
public class MarketingController {

    private static final MessagePostProcessor LOW_PRIORITY = message -> {
        message.getMessageProperties().setPriority(RabbitTopology.PRIORITY_MARKETING);
        return message;
    };

    private final RabbitTemplate rabbitTemplate;

    public record MarketingRequest(
            @Min(value = 1, message = "한 건 이상 넣어야 해요")
            @Max(value = 100_000, message = "한 번에 10만 건까지만 넣을 수 있어요")
            int count,

            @NotBlank(message = "문구가 필요해요")
            String message
    ) {
    }

    public record MarketingResponse(int enqueued) {
    }

    @PostMapping("/marketing")
    public ApiResponse<MarketingResponse> enqueue(@Valid @RequestBody MarketingRequest request) {
        for (int i = 0; i < request.count(); i++) {
            rabbitTemplate.convertAndSend(RabbitTopology.NOTIFY_EXCHANGE, RabbitTopology.RK_PUSH,
                    PushMessage.marketing(0, request.message()), LOW_PRIORITY);
        }
        return ApiResponse.ok(new MarketingResponse(request.count()));
    }
}
