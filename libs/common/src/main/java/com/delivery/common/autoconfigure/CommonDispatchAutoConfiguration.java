package com.delivery.common.autoconfigure;

import com.delivery.common.dispatch.CandidateList;
import com.delivery.common.dispatch.DispatchEventPublisher;
import com.delivery.common.dispatch.DispatchLease;
import com.delivery.common.dispatch.OfferBoard;
import com.delivery.common.dispatch.OfferProperties;
import com.delivery.common.dispatch.OfferSender;
import com.delivery.common.dispatch.KafkaOfferChannel;
import com.delivery.common.dispatch.RabbitOfferChannel;
import com.delivery.common.dispatch.OfferChannel;
import com.delivery.common.dispatch.RiderLock;
import com.delivery.common.dispatch.RiderState;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;

/**
 * 배차 공용 부품을 빈으로 올린다. dispatch-engine 과 offer-relay 가 같은 것을 받는다.
 *
 * <p>서비스마다 {@code @Component} 를 붙여 스캔하게 하는 대신 자동설정으로 올린 이유는
 * {@code common} 패키지가 서비스 패키지({@code com.delivery.dispatchengine} 등) 밖이라
 * 컴포넌트 스캔에 안 걸려서다. 서비스마다 {@code @Import} 를 붙이는 방법도 있지만 새 서비스를
 * 만들 때마다 빼먹게 된다.
 *
 * <p><b>이 바깥 클래스는 레디스도 래빗엠큐도 카프카도 참조하면 안 된다.</b> 처음에 Lua 스크립트를
 * 여기 static 필드로 뒀다가 location-ingest 가 기동에서 죽었다 —
 * {@code NoClassDefFoundError: org/springframework/data/redis/core/script/RedisScript}.
 * 안쪽 클래스에 {@code @ConditionalOnClass} 를 걸어놨는데도 그랬다.
 *
 * <p>순서를 보면 이유가 보인다. 스프링은 자동설정 클래스를 <b>먼저 로딩하고</b> 그 안의 조건을
 * 따진다. {@code @ConditionalOnClass} 는 ASM 으로 바이트코드만 읽어서 판단하기 때문에 클래스를
 * 안 불러오고도 걸러낼 수 있는데, <b>그 애노테이션이 붙은 클래스만</b> 지켜준다. 바깥 클래스가
 * 레디스 타입을 들고 있으면 바깥에서 먼저 터진다. 문을 잠갔는데 벽이 유리인 셈이다.
 *
 * <p>그래서 선택적 의존성을 건드리는 건 전부 안쪽 클래스로 내려보냈다.
 */
@AutoConfiguration(after = {
        RedisAutoConfiguration.class, RabbitAutoConfiguration.class, KafkaAutoConfiguration.class})
public class CommonDispatchAutoConfiguration {

    /** 레디스를 쓰는 서비스용 부품 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StringRedisTemplate.class)
    @EnableConfigurationProperties(OfferProperties.class)
    static class RedisParts {

        /**
         * Lua 스크립트는 빈으로 안 올리고 여기 상수로 둔다.
         *
         * <p>셋 다 타입이 {@code RedisScript<Long>} 이라 빈으로 올리면 주입이 <b>파라미터 이름</b>으로
         * 갈린다. 그런데 libs/common 은 스프링 부트 플러그인을 안 붙인 순수 라이브러리라
         * {@code -parameters} 컴파일 옵션이 없고, 스프링 6부터는 그게 없으면 파라미터 이름을
         * 못 읽는다. 그러면 기동할 때 "빈이 다섯인데 어느 걸 넣어야 할지 모르겠다" 로 죽거나,
         * 더 나쁘게는 엉뚱한 스크립트가 꽂힌다 — 락 해제 자리에 수락 스크립트가 들어가는 식이다.
         *
         * <p>{@link DefaultRedisScript} 는 본문의 SHA1 을 미리 계산해두고 EVALSHA 로 부른다.
         * 매번 스크립트 전문을 보내지 않아서, 배차마다 도는 이 자리에서는 그 차이가 쌓인다.
         */
        static final RedisScript<Long> RELEASE_LOCK = load("lua/release-lock.lua");
        /** DE-04 수락 / DE-05 거절 판정 */
        static final RedisScript<Long> RESPOND_OFFER = load("lua/respond-offer.lua");
        /** RE-02 만료 판정 */
        static final RedisScript<Long> EXPIRE_OFFER = load("lua/expire-offer.lua");
        /** OR-05 취소. 반환이 목록이라 이것만 타입이 다르다 */
        static final RedisScript<List> CANCEL_OFFER = loadList("lua/cancel-offer.lua");
        /** DE-02 후보 목록 저장 */
        static final RedisScript<Long> SAVE_CANDIDATES = load("lua/save-candidates.lua");
        /** DE-05 / RE-02 라이더 놓아주기 */
        static final RedisScript<Long> RELEASE_RIDER = load("lua/release-rider.lua");
        /** OR-04 배달 끝난 라이더 놓아주기 */
        static final RedisScript<Long> FINISH_DELIVERY = load("lua/finish-delivery.lua");

        @SuppressWarnings("rawtypes")
        private static RedisScript<List> loadList(String path) {
            DefaultRedisScript<List> script = new DefaultRedisScript<>();
            script.setLocation(new ClassPathResource(path));
            script.setResultType(List.class);
            return script;
        }

        private static RedisScript<Long> load(String path) {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>();
            script.setLocation(new ClassPathResource(path));
            // Long 으로 안 잡으면 반환값이 Integer 로 와서 -1 과 -2 를 가르는 switch 가 조용히 안 맞는다.
            script.setResultType(Long.class);
            return script;
        }

        @Bean
        @ConditionalOnBean(StringRedisTemplate.class)
        @ConditionalOnMissingBean
        OfferBoard offerBoard(StringRedisTemplate redis, OfferProperties properties) {
            return new OfferBoard(redis, RESPOND_OFFER, EXPIRE_OFFER, CANCEL_OFFER, properties);
        }

        @Bean
        @ConditionalOnBean(StringRedisTemplate.class)
        @ConditionalOnMissingBean
        CandidateList candidateList(StringRedisTemplate redis, OfferProperties properties) {
            return new CandidateList(redis, SAVE_CANDIDATES, properties);
        }

        @Bean
        @ConditionalOnBean(StringRedisTemplate.class)
        @ConditionalOnMissingBean
        DispatchLease dispatchLease(StringRedisTemplate redis, OfferProperties properties) {
            return new DispatchLease(redis, RELEASE_LOCK, properties);
        }

        @Bean
        @ConditionalOnBean(StringRedisTemplate.class)
        @ConditionalOnMissingBean
        RiderLock riderLock(StringRedisTemplate redis, OfferProperties properties) {
            return new RiderLock(redis, RELEASE_LOCK, properties);
        }

        @Bean
        @ConditionalOnBean(StringRedisTemplate.class)
        @ConditionalOnMissingBean
        RiderState riderState(StringRedisTemplate redis) {
            return new RiderState(redis, RELEASE_RIDER, FINISH_DELIVERY);
        }

        /** 어느 브로커로 보낼지(OfferChannel)는 쓸 때 꺼낸다. OfferSender#channel 주석 참고 */
        @Bean
        @ConditionalOnBean(StringRedisTemplate.class)
        @ConditionalOnMissingBean
        OfferSender offerSender(ObjectProvider<OfferChannel> channel, OfferBoard offerBoard,
                                CandidateList candidateList, RiderLock riderLock, RiderState riderState) {
            return new OfferSender(channel, offerBoard, candidateList, riderLock, riderState);
        }
    }

    /** 2단계 실험: delivery.offer.transport=rabbit(기본) 이면 제안이 래빗엠큐로 나간다 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RabbitTemplate.class)
    static class RabbitParts {

        @Bean
        @ConditionalOnBean(RabbitTemplate.class)
        @ConditionalOnMissingBean(OfferChannel.class)
        @ConditionalOnProperty(name = "delivery.offer.transport", havingValue = "rabbit", matchIfMissing = true)
        OfferChannel rabbitOfferChannel(RabbitTemplate rabbitTemplate) {
            return new RabbitOfferChannel(rabbitTemplate);
        }
    }

    /** 배차 결과 발행은 카프카를 쓰는 서비스만 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(KafkaTemplate.class)
    static class KafkaParts {

        @Bean
        @ConditionalOnBean(KafkaTemplate.class)
        @ConditionalOnMissingBean
        DispatchEventPublisher dispatchEventPublisher(KafkaTemplate<String, String> template) {
            return new DispatchEventPublisher(template);
        }

        @Bean
        @ConditionalOnBean(KafkaTemplate.class)
        @ConditionalOnMissingBean(OfferChannel.class)
        @ConditionalOnProperty(name = "delivery.offer.transport", havingValue = "kafka")
        OfferChannel kafkaOfferChannel(KafkaTemplate<String, String> template) {
            return new KafkaOfferChannel(template);
        }
    }
}
