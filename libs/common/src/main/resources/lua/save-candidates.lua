-- 후보 목록을 통째로 갈아끼운다. DEL → RPUSH → EXPIRE 를 한 덩어리로.
--
-- 자바에서 세 번 나눠 부르면 두 가지가 샌다.
--
-- 하나, 중간에 프로세스가 죽으면 이상한 게 남는다. DEL 만 하고 죽으면 후보가 빈 채로 남아서
-- 만료됐을 때 relay 가 "후보 소진" 으로 보고 배차를 실패시킨다. RPUSH 까지 하고 죽으면
-- TTL 없는 키가 영원히 남는다. 배차 상태를 전부 "TTL 이 알아서 치워준다" 로 설계해놨는데
-- 그 전제가 깨지는 자리다. 초당 200 주문이면 하루 1700만 개가 안 지워지고 쌓인다.
--
-- 둘, 왕복이 세 번이다. 레디스가 다른 노드면 왕복 한 번이 1ms 라 주문 하나에 3ms 다.
-- 초당 200 주문이면 여기서만 600ms 를 쓴다.
--
-- KEYS[1] = dispatch:candidates:{orderId}
-- ARGV[1] = TTL(초), ARGV[2..] = 점수순 riderId 들
-- 반환 = 저장한 후보 수
--
-- DEL 을 먼저 하는 게 중요하다. 재배차할 때 앞의 목록이 남아 있으면 같은 라이더가 리스트에
-- 두 번 들어가서, 이미 거절한 사람에게 또 제안이 간다.

redis.call('DEL', KEYS[1])

local count = #ARGV - 1
if count > 0 then
    -- 후보는 많아야 10명이라 unpack 으로 한 번에 넣어도 된다.
    -- (수천 개였다면 Lua 스택이 터져서 나눠 넣어야 한다)
    redis.call('RPUSH', KEYS[1], unpack(ARGV, 2))
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
return count
