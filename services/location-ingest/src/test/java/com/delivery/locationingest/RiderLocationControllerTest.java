package com.delivery.locationingest;

import com.delivery.common.exception.BusinessException;
import com.delivery.common.exception.ErrorCode;
import com.delivery.common.web.GlobalExceptionHandler;
import com.delivery.locationingest.api.RiderLocationController;
import com.delivery.locationingest.ingest.LocationIngestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 껍데기만 본다. 필터와 발행은 LocationIngestServiceTest 가 맡는다.
 *
 * <p>GlobalExceptionHandler 를 @Import 하는 이유는 OrderControllerTest 와 같다.
 * common 자동설정이 올리는 빈이라 @WebMvcTest 스캔에 안 걸려서, 안 넣으면 400 이 500 으로 나온다.
 */
@WebMvcTest(RiderLocationController.class)
@Import(GlobalExceptionHandler.class)
class RiderLocationControllerTest {

    private static final long RIDER = 881520076849260058L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LocationIngestService locationIngestService;

    @Test
    void returns202WithoutBody() throws Exception {
        mockMvc.perform(post("/api/riders/{riderId}/location", RIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "lat": 37.498095, "lng": 127.027610, "sentAt": "2026-09-23T04:12:33.482Z" }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        verify(locationIngestService).ingest(
                RIDER, 37.498095, 127.027610, Instant.parse("2026-09-23T04:12:33.482Z"));
    }

    @Test
    void outOfRangeReturns400InvalidCoordinate() throws Exception {
        willThrow(new BusinessException(ErrorCode.INVALID_COORDINATE))
                .given(locationIngestService).ingest(anyLong(), anyDouble(), anyDouble(), any());

        mockMvc.perform(post("/api/riders/{riderId}/location", RIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"lat\": 0, \"lng\": 0 }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COORDINATE"));
    }

    @Test
    void malformedBodyReturns400InvalidRequest() throws Exception {
        mockMvc.perform(post("/api/riders/{riderId}/location", RIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"lat\": \"여기\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
