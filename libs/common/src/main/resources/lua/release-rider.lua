-- 제안이 끝난 라이더를 놓아준다. 찜을 풀고 상태를 IDLE 로 되돌린다.
--
-- 둘 다 "내 것일 때만" 건드리는 게 요점이다. 조건 없이 IDLE 로 써버리면 이런 일이 난다.
--   t=3.00  라이더가 1번 주문을 거절한다. DE-05 가 찜을 풀고 IDLE 로 되돌린다
--   t=3.01  2번 주문이 이 라이더를 후보로 뽑아 찜하고 제안을 보낸다 (상태 OFFERED)
--   t=3.02  1번 주문의 만료 메시지가 offer-relay 에 도착한다
--           여기서 조건 없이 IDLE 을 쓰면, 2번 제안을 들고 있는 라이더가 "한가함" 이 된다
--   t=3.03  3번 주문이 이 라이더를 또 뽑는다 → 라이더 화면에 제안이 두 개 뜬다
--
-- 제안 보드에 걸어둔 펜싱 규칙(기능 정의서 3.9)과 같은 생각이다. 거기는 offerId 로 옛 메시지를
-- 걸러내고, 여기는 라이더가 지금 들고 있는 제안이 내가 보낸 그 제안인지를 본다.
--
-- KEYS[1] = rider:state:{riderId}, KEYS[2] = lock:rider:{riderId}
-- ARGV[1] = orderId, ARGV[2] = offerId, ARGV[3] = 지금(epoch ms)
--
-- 반환 = 상태를 IDLE 로 되돌렸으면 1, 이미 남의 제안을 들고 있어서 안 건드렸으면 0
--
-- (키 두 개를 한 스크립트에서 만진다. 레디스 클러스터로 가면 두 키가 같은 슬롯에 있어야 해서
--  해시태그가 필요한데, 지금은 단일 인스턴스라 그냥 둔다)

-- 찜은 값이 내 orderId 일 때만 푼다. TTL 12초가 지나 남이 새로 잡았을 수 있다.
if redis.call('GET', KEYS[2]) == ARGV[1] then
    redis.call('DEL', KEYS[2])
end

-- 상태는 "지금 들고 있는 제안" 이 내 offerId 일 때만 되돌린다.
-- 상태가 OFFERED 가 아니면(DELIVERING 이나 OFFLINE) 그것도 내가 건드릴 게 아니다.
if redis.call('HGET', KEYS[1], 'status') ~= 'OFFERED' then
    return 0
end
if redis.call('HGET', KEYS[1], 'offerId') ~= ARGV[2] then
    return 0
end

redis.call('HSET', KEYS[1],
    'status', 'IDLE',
    'idleSince', ARGV[3],
    'offerId', '',
    'currentOrderId', '')
return 1
