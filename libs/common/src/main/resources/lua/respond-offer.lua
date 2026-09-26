-- 라이더의 응답(수락 DE-04 / 거절 DE-05)을 확정한다. 읽고 비교하고 쓰는 걸 한 덩어리로 처리한다.
--
-- 자바에서 HGET 으로 읽고 if 로 보고 HSET 으로 쓰면 그 사이에 남이 끼어든다.
-- "레디스는 싱글 스레드니까 괜찮지 않나" 가 여기서 제일 흔한 오해인데, 싱글 스레드가
-- 지켜주는 건 명령 하나가 안 쪼개진다는 것뿐이다. 내 명령 세 개 사이는 안 지켜준다.
-- 은행 창구가 하나여도, 잔액 확인하고 밖에 나갔다 다시 와서 출금하면 그 사이에 남이 빼갈 수 있다.
--
-- 실제로 나는 장면: 라이더가 9.9초에 수락 버튼을 눌렀고 10.0초에 타이머가 만료됐다.
-- 둘이 각자 state 를 읽으면 둘 다 OFFERED 를 보고, 수락은 ACCEPTED 를 쓰고 만료는 EXPIRED 를 쓴다.
-- 나중에 쓴 쪽이 이겨서, 라이더 화면엔 "배차 완료" 가 뜨는데 주문은 2순위에게 넘어간다.
--
-- 수락과 거절이 같은 스크립트인 이유: 판정 절차가 글자 하나까지 같고 마지막에 쓰는 상태만 다르다.
-- 따로 두면 "offerId 를 riderId 보다 먼저 본다" 같은 규칙을 한쪽에만 고치는 날이 온다.
--
-- KEYS[1] = dispatch:offer:{orderId}
-- KEYS[2] = dispatch:outbox (수락일 때 내보낼 이벤트를 넣는 리스트)
-- ARGV[1] = offerId, ARGV[2] = riderId, ARGV[3] = 응답 시각(epoch ms), ARGV[4] = 쓸 상태
-- ARGV[5..] = 확정됐을 때 KEYS[2] 에 넣을 이벤트(JSON). 거절이면 안 준다
--
-- 반환  1 = 확정했다
--      -1 = 이미 수락된 제안이다        (409 ALREADY_TAKEN)
--      -2 = 만료됐거나 지난 제안이다     (410 OFFER_EXPIRED)
--       0 = 이 라이더의 제안이 아니다    (403 NOT_YOUR_OFFER)

local state = redis.call('HGET', KEYS[1], 'state')

-- 보드 자체가 없다. TTL 10분이 지났거나 아예 없던 주문이다.
if state == false then
    return -2
end

-- offerId 가 다르면 이미 다음 후보로 넘어간 뒤다. 이 검사를 riderId 검사보다 먼저 해야 한다.
-- 1순위가 만료돼서 2순위에게 넘어간 뒤 1순위가 뒤늦게 수락하면, 보드의 riderId 는 2순위라
-- riderId 부터 보면 "당신 제안이 아니에요"(403) 가 나간다. 라이더 입장에선 분명 자기한테 온
-- 제안이었으니 틀린 말이고, 맞는 말은 "만료됐어요"(410) 다.
if redis.call('HGET', KEYS[1], 'offerId') ~= ARGV[1] then
    return -2
end

if redis.call('HGET', KEYS[1], 'riderId') ~= ARGV[2] then
    return 0
end

-- 같은 사람이 버튼을 두 번 눌렀거나 앱이 재전송했다.
-- 거절하러 온 경우에도 이 답이 맞다 — 이미 수락한 제안은 거절할 수 없다.
if state == 'ACCEPTED' then
    return -1
end

-- EXPIRED / REJECTED / FAILED / CANCELLED. 어느 쪽이든 이제 와서 응답할 수는 없다.
if state ~= 'OFFERED' then
    return -2
end

-- respondedAt 은 수락이든 거절이든 "라이더가 답한 시각" 으로 같이 쓴다.
-- 수락일 때만 acceptedAt 을 따로 두면 거절 응답시간을 잴 자리가 없어진다.
redis.call('HSET', KEYS[1], 'state', ARGV[4], 'respondedAt', ARGV[3])

-- 레디스 아웃박스. 상태를 바꾸는 이 스크립트 안에서 이벤트도 같이 넣어야 한다.
-- 자바에서 ACCEPTED 를 쓴 뒤 카프카로 보내면, 그 사이에 죽었을 때 주문이 DISPATCHING 으로 영영 남는다.
-- 2단계 실험에서 dispatch-engine 을 kill -9 로 세 번 죽였더니 실제로 2, 1, 0건 남았다.
-- Lua 한 번은 통째로 돌거나 아예 안 도니까, 여기 넣으면 "상태는 바뀌었는데 이벤트가 없다" 가 안 생긴다.
for i = 5, #ARGV do
    redis.call('RPUSH', KEYS[2], ARGV[i])
end
return 1
