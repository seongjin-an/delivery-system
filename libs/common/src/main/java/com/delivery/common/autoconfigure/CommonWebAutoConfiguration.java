package com.delivery.common.autoconfigure;

import com.delivery.common.web.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * 서비스 8개가 GlobalExceptionHandler 를 각각 @Import 하지 않아도 되게 자동설정으로 올린다.
 *
 * <p>서비스 패키지는 com.delivery.orderapi 같은 식이라 com.delivery.common 은 컴포넌트 스캔에
 * 안 걸린다. 그래서 이 통로가 필요하다. 등록은 META-INF/spring/...AutoConfiguration.imports 에 있다.
 *
 * <p>@ConditionalOnMissingBean 을 붙여둬서, 어느 서비스가 자기만의 처리를 넣고 싶으면
 * 같은 타입 빈을 직접 정의하면 이건 물러난다.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
public class CommonWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
