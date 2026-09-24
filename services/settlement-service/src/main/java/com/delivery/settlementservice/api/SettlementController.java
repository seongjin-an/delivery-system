package com.delivery.settlementservice.api;

import com.delivery.common.response.ApiResponse;
import com.delivery.settlementservice.domain.SettlementQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/settlements")
@RequiredArgsConstructor
public class SettlementController {

    private final SettlementQuery settlementQuery;

    /**
     * SE-02. 일자별 건수와 거리 합, 수수료 합. 날짜는 한국 날짜다(yyyy-MM-dd).
     */
    @GetMapping
    public ApiResponse<SettlementQuery.Summary> find(
            @RequestParam long riderId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(settlementQuery.find(riderId, from, to));
    }
}
