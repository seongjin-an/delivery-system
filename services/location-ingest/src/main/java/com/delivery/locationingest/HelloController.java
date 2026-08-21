package com.delivery.locationingest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 뼈대 확인용. 이 모듈이 뜨는지, 포트가 제대로 잡혔는지만 본다.
 * 실제 기능이 들어오면 지울 파일 — TODO.md 1단계에서 정리한다.
 */
@RestController
public class HelloController {

    @GetMapping("/hello")
    public String hello() {
        return "location-ingest alive";
    }
}
