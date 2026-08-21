package com.delivery.geoindexer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** rider.location 소비 → 레디스 GEO 인덱스 갱신 */
@SpringBootApplication
public class GeoIndexerApplication {

    public static void main(String[] args) {
        SpringApplication.run(GeoIndexerApplication.class, args);
    }
}
