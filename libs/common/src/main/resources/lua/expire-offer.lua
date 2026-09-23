-- RE-02 만료 확정. 재제안을 시작해도 되는지 판정하고, 된다면 그 자리에서 state 를 바꾼다.
--
-- 왜 Lua 여야 하나. 여기서 막는 상대는 "라이더의 수락" 이다.
-- 수락은 HTTP 로 dispatch-engine 에 들어오고 배차 리스를 안 잡는다(잡을 이유가 없다. Lua CAS 로
-- 충분하니까). 그래서 offer-relay 가 리스를 쥐고 있어도 수락은 그 옆으로 그냥 지나간다.
-- 자바에서 읽고 판단하면 이런 순서가 실제로 난다.
--   t=0  relay 가 보드를 읽는다 → state=OFFERED, offerId 일치. "만료시켜도 되겠다"
--   t=1  라이더가 수락한다 → state=ACCEPTED
--   t=2  relay 가 2순위에게 재제안한다 → 이미 배차된 주문에 라이더가 한 명 더 붙는다
-- 읽기와 쓰기를 한 덩어리로 묶어야 t=1 이 끼어들 자리가 없어진다.
--
-- KEYS[1] = dispatch:offer:{orderId}
-- ARGV[1] = 메시지에 실려온 offerId, ARGV[2] = 만료 확정 시각(epoch ms)
--
-- 반환  1 = 만료 확정. 다음 후보로 넘어가라
--       0 = 펜싱에 걸렸다. 이미 다음 후보로 넘어간 뒤 도착한 옛날 메시지다 (기능 정의서 3.9)
--      -1 = 라이더가 이미 수락했다
--      -2 = 끝난 주문이다 (취소됐거나 후보를 다 썼다)
--      -3 = 보드가 없다. TTL 10분이 지났거나 처음 보는 주문이다

local state = redis.call('HGET', KEYS[1], 'state')

if state == false then
    return -3
end

-- 펜싱 규칙. 라이더가 3초에 거절해서 곧바로 2순위에게 넘어갔는데, 10초가 되면 1순위용
-- 타이머가 만료돼서 도착한다. 그걸 그대로 처리하면 아직 살아 있는 2순위 제안을 끊고
-- 3순위로 넘어간다. offerId 는 재제안마다 새로 발급하니 이 한 줄이 그걸 다 걸러낸다.
if redis.call('HGET', KEYS[1], 'offerId') ~= ARGV[1] then
    return 0
end

if state == 'ACCEPTED' then
    return -1
end

if state == 'CANCELLED' or state == 'FAILED' then
    return -2
end

if state == 'OFFERED' then
    redis.call('HSET', KEYS[1], 'state', 'EXPIRED', 'expiredAt', ARGV[2])
    return 1
end

-- 여기 남는 건 EXPIRED 와 REJECTED 둘뿐이고, 둘 다 "재제안해라" 가 맞다.
--
-- REJECTED 는 정상 경로다. DE-05 가 라이더 거절을 받으면 10초를 기다릴 이유가 없으니
-- state 를 REJECTED 로 바꾸고 dispatch.dlx 에 만료 메시지를 직접 넣는다. 그게 여기로 온다.
-- 상태를 EXPIRED 로 덮지 않는 건, "안 받았다" 와 "거절했다" 가 지표에서 다른 이야기라서다.
--
-- EXPIRED 는 relay 가 만료까지만 찍고 재제안 전에 죽어서 메시지가 재전달된 경우다.
-- 재제안이 아직 안 나갔으니 이어서 하면 된다.
return 1
