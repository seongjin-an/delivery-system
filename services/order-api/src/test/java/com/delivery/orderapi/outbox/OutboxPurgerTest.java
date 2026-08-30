package com.delivery.orderapi.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxPurgerTest {

    @Mock
    private OutboxRepository outboxRepository;

    @InjectMocks
    private OutboxPurger outboxPurger;

    private void givenPurgeAfterMinutes(long minutes) {
        ReflectionTestUtils.setField(outboxPurger, "purgeAfterMinutes", minutes);
    }

    /** 방금 들어온 행까지 지워버리면 "이벤트가 들어가긴 했나" 를 확인할 방법이 없어진다 */
    @Test
    void deletesOnlyRowsOlderThanConfiguredAge() {
        givenPurgeAfterMinutes(60);
        given(outboxRepository.deleteCreatedBefore(any())).willReturn(3);

        outboxPurger.purge();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(outboxRepository).deleteCreatedBefore(cutoff.capture());
        Instant expected = Instant.now().minus(Duration.ofMinutes(60));
        assertThat(cutoff.getValue()).isCloseTo(expected, org.assertj.core.api.Assertions.within(
                5, java.time.temporal.ChronoUnit.SECONDS));
    }

    /**
     * 예외가 새어 나가면 스프링 스케줄러가 이 작업을 아예 다시 안 돌린다.
     * 그러면 아웃박스가 조용히 쌓이기만 하고 아무도 모른다.
     */
    @Test
    void swallowsExceptionSoSchedulerKeepsRunning() {
        givenPurgeAfterMinutes(60);
        willThrow(new IllegalStateException("DB 가 죽었다"))
                .given(outboxRepository).deleteCreatedBefore(any());

        assertThatCode(() -> outboxPurger.purge()).doesNotThrowAnyException();
    }
}
