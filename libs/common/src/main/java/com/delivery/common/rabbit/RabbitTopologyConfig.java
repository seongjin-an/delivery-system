package com.delivery.common.rabbit;

import com.delivery.common.JsonUtil;
import com.delivery.common.RabbitTopology;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
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
 *                                                                │ 재제안 3회 실패
 *                        dispatch.dlx ──(offer.dead)─────▶ dispatch.offer.expired.dlq
 * </pre>
 *
 * <p><b>타이머 큐에 리스너를 붙이면 안 된다.</b> 붙이면 메시지를 즉시 꺼내가서 TTL 이 흐를 시간이
 * 없다. 그러면 재제안이 한 번도 안 돈다. 처음 만들 때 여기서 한참 헤맬 만한 부분이다.
 *
 * <p><b>왜 common 에 뒀나.</b> 기능 정의서는 offer-relay 가 선언한다고 적어뒀는데, 그러면
 * dispatch-engine 이 offer-relay 보다 먼저 뜨면 큐가 없어서 메시지가 조용히 버려진다.
 * 여기 두고 amqp 를 쓰는 서비스가 다 같이 선언하면 그 순서 문제가 사라진다.
 * 스프링 AMQP 의 선언은 멱등이라 여러 서비스가 같은 걸 선언해도 문제가 없다.
 *
 * <p><b>다만 "같은 걸" 일 때만 멱등이다.</b> 이미 있는 durable 큐에 인자를 새로 붙여서 다시
 * 선언하면 브로커가 거절한다. 만료 큐에 DLX 를 새로 붙였을 때 실제로 당했다.
 *
 * <pre>
 * PRECONDITION_FAILED - inequivalent arg 'x-dead-letter-exchange'
 *   for queue 'dispatch.offer.expired': received 'dispatch.dlx' but current is none
 * </pre>
 *
 * <p><b>그런데 앱은 멀쩡히 뜬다.</b> 기동 스크립트도 "offer-relay is up" 이라고 찍는다.
 * 선언에 실패한 채널만 닫히고 그 큐에 리스너가 안 붙을 뿐이다. 그래서 겉보기엔 다 정상인데
 * 만료 제안이 한 건도 처리되지 않는다. Debezium 커넥터가 RUNNING 인데 이벤트가 안 나가던 것과
 * 똑같은 모양이다 — <b>떴다고 일이 되고 있는 게 아니다.</b>
 *
 * <p>인자는 나중에 못 바꾸니 큐를 지우고 다시 띄우는 수밖에 없다.
 * <pre>docker exec delivery-rabbitmq rabbitmqctl delete_queue dispatch.offer.expired</pre>
 * 확인은 컨슈머 수로 한다. 만료 큐의 consumers 가 0이면 리스너가 안 붙은 것이다.
 * <pre>docker exec delivery-rabbitmq rabbitmqctl list_queues name messages consumers</pre>
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

        // 만료 큐 — offer-relay 가 꺼내서 다음 후보에게 재제안한다.
        //
        // 여기에도 DLX 를 건다. 재제안이 3회 재시도로도 안 되면 스프링이 requeue 없이 버리는데,
        // 그냥 버리면 그 주문은 재제안을 영영 못 받는다. 라우팅 키를 offer.dead 로 바꿔서
        // 같은 dispatch.dlx 를 타고 별도 큐로 보낸다 — offer.expired 를 그대로 쓰면
        // 자기 큐로 도로 들어와서 무한히 돈다.
        Queue expired = QueueBuilder.durable(RabbitTopology.Q_OFFER_EXPIRED)
                .deadLetterExchange(RabbitTopology.DISPATCH_DLX)
                .deadLetterRoutingKey(RabbitTopology.RK_OFFER_DEAD)
                .build();

        Queue expiredDlq = QueueBuilder.durable(RabbitTopology.Q_OFFER_EXPIRED_DLQ).build();

        return new Declarables(
                dispatchExchange, dispatchDlx,
                notify, timer, expired, expiredDlq,
                bind(notify, dispatchExchange, RabbitTopology.RK_OFFER_CREATED),
                bind(timer, dispatchExchange, RabbitTopology.RK_OFFER_CREATED),
                bind(expired, dispatchDlx, RabbitTopology.RK_OFFER_EXPIRED),
                bind(expiredDlq, dispatchDlx, RabbitTopology.RK_OFFER_DEAD));
    }

    /**
     * 알림 발송 토폴로지 (NW-01, NW-02, NW-03).
     *
     * <p>상수만 있고 선언이 없었다. 그 상태로 NW-01 이 notify.x 에 보내면 받을 큐가 없어서 메시지가
     * 에러도 없이 사라진다. RE-01 때 dispatch 토폴로지를 여기로 모은 것과 같은 이유로 여기 둔다.
     *
     * <p>notify.push 는 우선순위 큐다. 배차 제안(9)과 마케팅(1)이 같은 큐에 섞여 들어가야
     * "마케팅 1만 건 뒤에 들어온 제안이 먼저 나간다" 를 볼 수 있다. 큐를 둘로 나누면 우선순위가 아니라
     * 컨슈머 배분 문제가 된다.
     *
     * <p><b>x-max-priority 는 큐를 만들 때만 정해진다.</b> 이 줄 없이 한 번이라도 떠서 큐가 생겼다면
     * PRECONDITION_FAILED 로 기동이 막힌다. 그때는 큐를 지우고 다시 띄운다.
     */
    @Bean
    public Declarables notifyTopology() {
        DirectExchange notifyExchange = new DirectExchange(RabbitTopology.NOTIFY_EXCHANGE, true, false);
        DirectExchange notifyDlx = new DirectExchange(RabbitTopology.NOTIFY_DLX, true, false);

        Queue push = QueueBuilder.durable(RabbitTopology.Q_PUSH)
                .maxPriority(RabbitTopology.PUSH_MAX_PRIORITY)
                .deadLetterExchange(RabbitTopology.NOTIFY_DLX)
                .deadLetterRoutingKey(RabbitTopology.RK_PUSH_DEAD)
                .build();
        Queue pushDlq = QueueBuilder.durable(RabbitTopology.Q_PUSH_DLQ).build();

        return new Declarables(
                notifyExchange, notifyDlx, push, pushDlq,
                BindingBuilder.bind(push).to(notifyExchange).with(RabbitTopology.RK_PUSH),
                BindingBuilder.bind(pushDlq).to(notifyDlx).with(RabbitTopology.RK_PUSH_DEAD));
    }

    private static Binding bind(Queue queue, TopicExchange exchange, String routingKey) {
        return BindingBuilder.bind(queue).to(exchange).with(routingKey);
    }
}
