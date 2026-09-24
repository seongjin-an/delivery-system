-- OR-05 주문 취소. 제안 보드를 무조건 CANCELLED 로 바꾸고, 바꾸기 전 모습을 돌려준다.
--
-- 읽고 쓰기를 한 덩어리로 묶는 이유는 수락이다. 수락(DE-04)은 리스를 안 잡고 respond-offer.lua 로
-- OFFERED → ACCEPTED 를 바꾼다. 자바에서 "OFFERED 네, 취소하자" 하고 쓰는 사이에 수락이 끼어들면
-- 취소된 주문에 라이더가 배차된다. 여기서 한 번에 바꾸면 수락은 CANCELLED 를 보고 410 을 받는다.
--
-- 보드가 없어도 만든다. 아직 제안이 한 번도 안 나간 주문(CREATED, 배차 대기 중)을 취소한 경우다.
-- 이걸 안 만들면 뒤늦게 order.created 를 읽은 dispatch-engine 이 "처음 보는 주문" 으로 보고 배차를 시작한다.
-- CANCELLED 보드가 있으면 dispatch-engine 의 shouldProceed 가 보고 멈춘다.
--
-- KEYS[1] = dispatch:offer:{orderId}
-- ARGV[1] = 취소 시각(epoch ms), ARGV[2] = 보드 TTL(초)
--
-- 반환 = { 바꾸기 전 state(없었으면 NONE), riderId, offerId }

local state = redis.call('HGET', KEYS[1], 'state')
local riderId = redis.call('HGET', KEYS[1], 'riderId') or ''
local offerId = redis.call('HGET', KEYS[1], 'offerId') or ''

redis.call('HSET', KEYS[1], 'state', 'CANCELLED', 'cancelledAt', ARGV[1])
redis.call('EXPIRE', KEYS[1], ARGV[2])

if state == false then
    return {'NONE', '', ''}
end
return {state, riderId, offerId}
