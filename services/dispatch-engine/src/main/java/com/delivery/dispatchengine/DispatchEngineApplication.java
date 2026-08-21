package com.delivery.dispatchengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** order.created 소비 → 후보 검색 → 배차 제안 발행 */
@SpringBootApplication
public class DispatchEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(DispatchEngineApplication.class, args);
    }
}
