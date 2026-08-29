package com.delivery.common.autoconfigure;

import com.delivery.common.kafka.ConsumerErrorHandlerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * 컨슈머 공통 에러 핸들러를 빈으로 올린다.
 *
 * <p>스프링 부트가 CommonErrorHandler 타입 빈이 있으면 리스너 컨테이너 팩토리에 알아서 꽂아준다.
 * 그래서 각 서비스는 아무것도 안 해도 3.8 규칙(BusinessException 은 즉시 DLT, 나머지는 3회 백오프)을
 * 그대로 받는다.
 *
 * <p>조건이 두 개 붙어 있다. 스프링 카프카가 클래스패스에 있어야 하고(notification-worker 랑
 * rider-simulator 는 카프카를 안 쓴다), DLT 로 발행할 KafkaTemplate 빈이 있어야 한다.
 * 그래서 KafkaAutoConfiguration 이 끝난 다음에 평가되도록 순서를 잡았다.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnClass({KafkaTemplate.class, CommonErrorHandler.class})
public class CommonKafkaAutoConfiguration {

    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    public DefaultErrorHandler kafkaConsumerErrorHandler(KafkaTemplate<?, ?> template) {
        return ConsumerErrorHandlerFactory.create(template);
    }
}
