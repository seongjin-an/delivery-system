package com.delivery.common.autoconfigure;

import com.delivery.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 컨슈머 공통 에러 핸들러가 빈으로 올라오는지, 그리고 BusinessException 이 재시도 대상에서 빠졌는지 본다.
 *
 * <p>브로커는 안 띄운다. KafkaTemplate 빈을 만드는 것만으로는 아무 데도 접속하지 않는다.
 */
class CommonKafkaAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    KafkaAutoConfiguration.class, CommonKafkaAutoConfiguration.class));

    @Test
    void registersErrorHandlerWhenKafkaTemplateExists() {
        runner.run(context -> assertThat(context).hasSingleBean(DefaultErrorHandler.class));
    }

    /**
     * 이게 3.8 규칙의 핵심이다. BusinessException 은 "재시도 안 함(false)" 으로 분류돼 있어야
     * 첫 실패에 바로 DLT 로 간다.
     *
     * <p>removeClassification 은 분류를 빼면서 그 값을 돌려준다. 분류가 아예 없으면 null 이 온다.
     */
    @Test
    void marksBusinessExceptionAsNotRetryable() {
        runner.run(context -> {
            DefaultErrorHandler handler = context.getBean(DefaultErrorHandler.class);
            assertThat(handler.removeClassification(BusinessException.class)).isFalse();
        });
    }

    /** 스프링 카프카 자체가 없는 서비스(notification-worker, rider-simulator)에서는 켜지면 안 된다 */
    @Test
    void skipsWhenSpringKafkaIsAbsent() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommonKafkaAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(KafkaTemplate.class, CommonErrorHandler.class))
                .run(context -> assertThat(context).doesNotHaveBean("kafkaConsumerErrorHandler"));
    }
}
