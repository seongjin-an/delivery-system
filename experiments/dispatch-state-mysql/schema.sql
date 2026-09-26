-- 2단계 실험: 레디스 dispatch:offer:{orderId} 해시와 lock:dispatch:{orderId} 를 행 하나로 합친 것.
-- 시각은 레디스 쪽과 똑같이 epoch 밀리초 정수로 둔다. 코드에서 비교할 때 변환이 안 끼게.
DROP TABLE IF EXISTS order_dispatch;
CREATE TABLE order_dispatch (
    order_id     BIGINT       NOT NULL PRIMARY KEY,
    offer_id     BIGINT       NULL,
    rider_id     BIGINT       NULL,
    state        VARCHAR(16)  NULL,
    attempt      INT          NOT NULL DEFAULT 0,
    offered_at   BIGINT       NULL,
    responded_at BIGINT       NULL,
    expired_at   BIGINT       NULL,
    cancelled_at BIGINT       NULL,
    lease_owner  VARCHAR(100) NULL,
    lease_until  BIGINT       NULL,
    -- 라이더는 offerId 만 들고 수락하러 온다. 레디스에선 인덱스 키를 따로 둬야 했다
    KEY idx_order_dispatch_offer (offer_id)
) ENGINE = InnoDB;
