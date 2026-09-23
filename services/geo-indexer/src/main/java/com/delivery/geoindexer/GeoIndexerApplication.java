package com.delivery.geoindexer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** rider.location 소비 → 레디스 GEO 인덱스 갱신, 조용한 라이더 오프라인 정리 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GeoIndexerApplication {

    public static void main(String[] args) {
        SpringApplication.run(GeoIndexerApplication.class, args);
    }
}
