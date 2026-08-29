package com.delivery.common.autoconfigure;

import com.delivery.common.web.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.DispatcherServlet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자동설정이 실제로 켜지는지 본다.
 *
 * <p>이걸 굳이 테스트로 두는 이유가 있다. 자동설정은 잘못돼도 아무 소리 없이 그냥 안 켜진다.
 * 그러면 예외가 전부 500 으로 나가는데, 서비스를 다 만들고 나서야 "어? 400 이 왜 500 이지" 로 알게 된다.
 */
class CommonWebAutoConfigurationTest {

    private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonWebAutoConfiguration.class));

    @Test
    void registersGlobalExceptionHandlerOnWebApplication() {
        webRunner.run(context -> assertThat(context).hasSingleBean(GlobalExceptionHandler.class));
    }

    /** 서비스가 자기 핸들러를 직접 정의하면 공통 것은 물러나야 한다 */
    @Test
    void backsOffWhenApplicationDefinesItsOwnHandler() {
        webRunner.withUserConfiguration(OwnHandlerConfig.class)
                .run(context -> assertThat(context)
                        .hasSingleBean(GlobalExceptionHandler.class)
                        .getBean(GlobalExceptionHandler.class)
                        .isSameAs(OwnHandlerConfig.INSTANCE));
    }

    /** 웹이 아닌 애플리케이션에서는 안 올라온다 */
    @Test
    void skipsOnNonWebApplication() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommonWebAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(GlobalExceptionHandler.class));
    }

    /** 스프링 MVC 가 클래스패스에 없어도 조용히 비켜서야 한다 (기동이 깨지면 안 된다) */
    @Test
    void skipsWhenSpringMvcIsAbsent() {
        webRunner.withClassLoader(new FilteredClassLoader(DispatcherServlet.class))
                .run(context -> assertThat(context).doesNotHaveBean(GlobalExceptionHandler.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnHandlerConfig {

        static final GlobalExceptionHandler INSTANCE = new GlobalExceptionHandler();

        @Bean
        GlobalExceptionHandler myOwnHandler() {
            return INSTANCE;
        }
    }
}
