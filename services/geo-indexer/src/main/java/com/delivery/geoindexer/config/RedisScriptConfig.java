package com.delivery.geoindexer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * GI-01 인덱싱 스크립트를 빈으로 올린다.
 *
 * <p>공용 스크립트들은 {@code libs/common} 의 자동설정에 있는데 이건 여기 둔다.
 * {@code riders:online} 과 {@code riders:heartbeat} 에 <b>쓰는</b> 서비스는 geo-indexer 뿐이다.
 * dispatch-engine 은 GEOSEARCH 로 읽기만 한다. 두 서비스가 같은 코드를 돌리는 게 아니라
 * 공용으로 올릴 이유가 없다 — 필드 이름이 어긋날 위험은 {@code RiderStateFields} 가 이미 막는다.
 */
@Configuration(proxyBeanMethods = false)
public class RedisScriptConfig {

    @Bean
    public RedisScript<Long> indexRiderScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/index-rider.lua"));
        script.setResultType(Long.class);
        return script;
    }

    /** GI-02 오프라인 정리. 라이더 한 명의 판정과 정리를 한 덩어리로 한다 */
    @Bean
    public RedisScript<Long> sweepRiderScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/sweep-rider.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
