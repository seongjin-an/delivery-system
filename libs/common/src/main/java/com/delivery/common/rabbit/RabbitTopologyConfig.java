package com.delivery.common.rabbit;

import com.delivery.common.JsonUtil;
import com.delivery.common.RabbitTopology;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 래빗엠큐 토폴로지 선언. 기능 정의서 RE-01.
 *
 * <p><b>이게 래빗엠큐를 쓰는 이유 전부다.</b> "특정 한 명에게, 10초 안에, 안 받으면 다음 사람" 을
 * 카프카로는 만들 수 없다.
 *
 * <p>구조는 이렇다. {@code dispatch.x} 에 라우팅 키 {@code offer.created} 로 <b>한 번</b> 발행하면
 * 브로커가 큐 두 개로 복제해준다.
 *
 * <pre>
 * dispatch.x  ──(offer.created)──┬─▶ dispatch.offer.notify  (워커가 즉시 꺼내 푸시 발송)
 *                                └─▶ dispatch.offer.timer   (아무도 안 꺼낸다. 10초를 센다)
 *                                        │ 10초 뒤 TTL 만료
 *                                        ▼
 *                        dispatch.dlx ──(offer.expired)──▶ dispatch.offer.expired
 * </pre>
 *
 * <p><b>타이머 큐에 리스너를 붙이면 안 된다.</b> 붙이면 메시지를 즉시 꺼내가서 TTL 이 흐를 시간이
 * 없다. 그러면 재제안이 한 번도 안 돈다. 처음 만들 때 여기서 한참 헤맬 만한 부분이다.
 *
 * <p><b>왜 common 에 뒀나.</b> 기능 정의서는 offer-relay 가 선언한다고 적어뒀는데, 그러면
 * dispatch-engine 이 offer-relay 보다 먼저 뜨면 큐가 없어서 메시지가 조용히 버려진다.
 * 여기 두고 amqp 를 쓰는 서비스가 다 같이 선언하면 그 순서 문제가 사라진다.
 * 스프링 AMQP 의 선언은 멱등이라 여러 서비스가 같은 걸 선언해도 문제가 없다.
 */
@Configuration(proxyBeanMethods = false)
public class RabbitTopologyConfig {

    /**
     * 메시지를 JSON 으로 주고받는다.
     *
     * <p>기본 컨버터는 자바 직렬화라서 DispatchOffer 같은 record 를 그냥 못 보낸다.
     * 게다가 자바 직렬화로 보내면 래빗엠큐 관리 UI 에서 메시지를 열어봐도 알아볼 수가 없다.
     * 프론트가 없어서 큐를 눈으로 들여다볼 일이 많은 프로젝트라 그게 꽤 아쉽다.
     *
     * <p>ObjectMapper 는 JsonUtil 것을 그대로 쓴다. 카프카로 나가는 이벤트와 시각 표기가
     * 달라지면 안 되기 때문이다.
     */
    @Bean
    @ConditionalOnMissingBean(MessageConverter.class)
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter(JsonUtil.mapper());
    }

    @Bean
    public Declarables dispatchTopology() {
        TopicExchange dispatchExchange = new TopicExchange(RabbitTopology.DISPATCH_EXCHANGE, true, false);
        TopicExchange dispatchDlx = new TopicExchange(RabbitTopology.DISPATCH_DLX, true, false);

        // 알림 큐 — notification-worker 가 바로 꺼내간다
        Queue notify = QueueBuilder.durable(RabbitTopology.Q_OFFER_NOTIFY).build();

        // 타이머 큐 — 컨슈머가 없다. 10초가 지나면 DLX 로 떨어지면서 라우팅 키가 offer.expired 로 바뀐다.
        Queue timer = QueueBuilder.durable(RabbitTopology.Q_OFFER_TIMER)
                .ttl(RabbitTopology.OFFER_TTL_MS)
                .deadLetterExchange(RabbitTopology.DISPATCH_DLX)
                .deadLetterRoutingKey(RabbitTopology.RK_OFFER_EXPIRED)
                .build();

        // 만료 큐 — offer-relay 가 꺼내서 다음 후보에게 재제안한다
        Queue expired = QueueBuilder.durable(RabbitTopology.Q_OFFER_EXPIRED).build();

        return new Declarables(
                dispatchExchange, dispatchDlx,
                notify, timer, expired,
                bind(notify, dispatchExchange, RabbitTopology.RK_OFFER_CREATED),
                bind(timer, dispatchExchange, RabbitTopology.RK_OFFER_CREATED),
                bind(expired, dispatchDlx, RabbitTopology.RK_OFFER_EXPIRED));
    }

    private static Binding bind(Queue queue, TopicExchange exchange, String routingKey) {
        return BindingBuilder.bind(queue).to(exchange).with(routingKey);
    }
}
