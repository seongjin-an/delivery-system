-- OR-04 배달을 끝낸 라이더를 놓아준다. 상태를 IDLE 로 되돌린다.
--
-- 기능 정의서 OR-04 규칙 2번은 조건 없이 HSET status IDLE 인데, 그러면 재시도 한 번에 깨진다.
--
--   t=0.0  라이더 앱이 완료를 보낸다 → DB 는 DELIVERED, 레디스는 IDLE
--   t=0.8  다른 주문이 이 라이더에게 제안을 보낸다 → status = OFFERED
--   t=1.2  첫 응답이 네트워크에서 늦어서 앱이 완료를 한 번 더 보낸다
--          order-api 는 "이미 DELIVERED 네" 하고 200 을 주면서 레디스 정리를 한 번 더 한다
--          여기서 조건 없이 IDLE 을 쓰면, 제안을 들고 있는 라이더가 한가한 사람이 된다
--
-- 레디스 정리를 두 번째 요청에서도 하는 이유는 첫 요청이 DB 커밋 뒤 레디스에서 실패했을 수 있어서다.
-- 그래서 "지금 배달 중인 주문이 이 주문일 때만" 되돌린다. release-rider.lua 와 같은 생각이다.
--
-- KEYS[1] = rider:state:{riderId}
-- ARGV[1] = orderId, ARGV[2] = 지금(epoch ms)
--
-- 반환 1 = IDLE 로 되돌렸다, 0 = 이미 다른 상태라 안 건드렸다

if redis.call('HGET', KEYS[1], 'status') ~= 'DELIVERING' then
    return 0
end
if redis.call('HGET', KEYS[1], 'currentOrderId') ~= ARGV[1] then
    return 0
end

redis.call('HSET', KEYS[1],
    'status', 'IDLE',
    'idleSince', ARGV[2],
    'offerId', '',
    'currentOrderId', '')
return 1
