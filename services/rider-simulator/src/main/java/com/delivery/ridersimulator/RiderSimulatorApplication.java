package com.delivery.ridersimulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** 라이더/주문 트래픽 생성기 — 프론트 대신 쓰는 손잡이 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RiderSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(RiderSimulatorApplication.class, args);
    }
}
