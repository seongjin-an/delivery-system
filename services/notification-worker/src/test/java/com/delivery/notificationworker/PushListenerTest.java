package com.delivery.notificationworker;

import com.delivery.common.event.PushMessage;
import com.delivery.notificationworker.push.PushDelivery;
import com.delivery.notificationworker.push.PushDelivery.Outcome;
import com.delivery.notificationworker.push.PushListener;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** PushDelivery 의 결과가 ack/nack 으로 제대로 바뀌는지만 본다 */
class PushListenerTest {

    private static final long TAG = 42L;

    private final PushDelivery delivery = mock(PushDelivery.class);
    private final Channel channel = mock(Channel.class);
    private final PushListener listener = new PushListener(delivery);
    private final PushMessage message = PushMessage.marketing(0, "점심 할인");

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"SENT", "EXPIRED"})
    void acksWhenDoneOrPointless(Outcome outcome) throws Exception {
        given(delivery.deliver(message)).willReturn(outcome);

        listener.onPush(message, channel, TAG);

        verify(channel).basicAck(TAG, false);
    }

    /** requeue=false 라야 notify.dlx 를 타고 DLQ 로 간다. true 면 곧바로 다시 들어와서 끝없이 돈다 */
    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"RATE_LIMITED", "DEAD"})
    void nacksWithoutRequeueSoItLandsInDlq(Outcome outcome) throws Exception {
        given(delivery.deliver(message)).willReturn(outcome);

        listener.onPush(message, channel, TAG);

        verify(channel).basicNack(TAG, false, false);
    }
}
