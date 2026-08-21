package com.delivery.locationingest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 라이더 위치 수신 → rider.location 발행 (무상태) */
@SpringBootApplication
public class LocationIngestApplication {

    public static void main(String[] args) {
        SpringApplication.run(LocationIngestApplication.class, args);
    }
}
