package com.delivery.locationingest.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * 카프카 클러스터 ID 를 미리 넣어둔다.
 *
 * <p>template.observation-enabled 를 켜면 KafkaTemplate 이 send() 할 때마다 스팬 태그에 붙일
 * 클러스터 ID 를 찾는다. 처음 한 번은 AdminClient 로 브로커에 물어보는데, 이걸 전역 락 하나 잡고
 * 30초 타임아웃으로 한다. 실패하면 캐시도 안 해서 다음 요청이 또 30초를 기다린다.
 *
 * <p>실제로 카프카를 내린 채 띄워봤더니 좌표 요청이 전부 10초 넘게 멈췄고, 스레드 덤프를 떠보니
 * 요청 스레드들이 KafkaTemplate.clusterId() 의 락 앞에 줄 서 있었다. max.block.ms 500 을 걸어둔 게
 * 아무 소용이 없었다. 여기는 p99 20ms 가 목표인 서비스라 이러면 톰캣 스레드가 다 묶여서 같이 죽는다.
 *
 * <p>값은 infra/compose.yaml 의 CLUSTER_ID 와 같다. 틀려도 스팬 태그 하나가 틀리는 것뿐이고
 * 발행에는 영향이 없다. 비워두면 원래대로 브로커에 물어본다.
 */
@Configuration
public class KafkaClusterIdConfig {

    @Bean
    public static BeanPostProcessor kafkaClusterIdSetter(
            @Value("${delivery.ingest.kafka-cluster-id:}") String clusterId) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof KafkaAdmin admin && !clusterId.isBlank()) {
                    admin.setClusterId(clusterId);
                }
                return bean;
            }
        };
    }
}
