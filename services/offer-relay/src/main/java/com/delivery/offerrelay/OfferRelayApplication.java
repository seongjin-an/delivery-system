package com.delivery.offerrelay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 만료 제안(DLX) 수신 → 다음 후보에게 재제안 */
@SpringBootApplication
public class OfferRelayApplication {

    public static void main(String[] args) {
        SpringApplication.run(OfferRelayApplication.class, args);
    }
}
