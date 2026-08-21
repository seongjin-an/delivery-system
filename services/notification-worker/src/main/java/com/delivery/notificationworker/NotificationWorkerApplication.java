package com.delivery.notificationworker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 푸시/SMS 발송 워커 (외부 API 레이트리밋 대응) */
@SpringBootApplication
public class NotificationWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationWorkerApplication.class, args);
    }
}
