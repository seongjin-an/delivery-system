package com.delivery.dispatchengine.candidate;

/** 거리만 아는 단계의 결과. 2단계 실험에서 레디스와 MySQL 이 같은 모양으로 돌려주려고 뺐다 */
public record Nearby(long riderId, double distanceMeters) {
}
