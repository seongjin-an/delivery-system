package com.delivery.common.dispatch;

import com.delivery.common.exception.ErrorCode;

/**
 * {@code respond-offer.lua} 의 반환값. 기능 정의서 DE-04 의 표 그대로다.
 *
 * <p>수락과 거절이 같은 타입을 쓴다. 판정 절차가 같으니 나올 수 있는 답도 같다.
 *
 * <p>실패를 하나로 뭉치면 라이더가 왜 안 되는지 알 수 없다. "이미 다른 분이 받았어요" 와
 * "제안이 만료됐어요" 는 라이더가 다음에 할 행동이 다르다 — 앞은 그냥 넘어가면 되고,
 * 뒤는 곧 다른 제안이 올 수 있으니 앱을 켜둬야 한다. 시뮬레이터도 이 둘을 갈라서 세야 해서
 * 사람이 읽는 message 말고 기계가 볼 code 가 따로 필요하다.
 */
public enum OfferDecision {

    /** 응답이 확정됐다 (수락이면 배차 확정, 거절이면 다음 후보로) */
    APPLIED(1, null),
    /** 이미 수락된 제안이다 (같은 사람이 두 번 눌렀거나 앱이 재전송했다) */
    ALREADY_TAKEN(-1, ErrorCode.ALREADY_TAKEN),
    /** 만료됐거나 이미 다음 후보로 넘어간 제안이다 */
    EXPIRED(-2, ErrorCode.OFFER_EXPIRED),
    /** 이 라이더에게 간 제안이 아니다 */
    NOT_YOURS(0, ErrorCode.NOT_YOUR_OFFER);

    private final long code;
    private final ErrorCode errorCode;

    OfferDecision(long code, ErrorCode errorCode) {
        this.code = code;
        this.errorCode = errorCode;
    }

    public boolean isApplied() {
        return this == APPLIED;
    }

    /** 성공일 땐 null. 던지는 쪽에서 확인하고 쓴다 */
    public ErrorCode errorCode() {
        return errorCode;
    }

    static OfferDecision of(Long returned) {
        if (returned == null) {
            // 스크립트가 아무것도 안 돌려주는 경우는 없어야 한다. 그래도 왔다면 레디스 쪽이
            // 이상한 거라 수락으로 처리하면 안 된다. 만료로 떨어뜨려서 라이더가 다시 시도하게 둔다.
            return EXPIRED;
        }
        for (OfferDecision decision : values()) {
            if (decision.code == returned) {
                return decision;
            }
        }
        throw new IllegalStateException("응답 스크립트가 모르는 값을 돌려줬다: " + returned);
    }
}
