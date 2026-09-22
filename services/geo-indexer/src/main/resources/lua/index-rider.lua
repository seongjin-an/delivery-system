-- GI-01 라이더 한 명의 위치를 인덱스에 반영한다. 키 세 개를 한 덩어리로 건드린다.
--
-- 왜 Lua 냐면 4번 규칙(status 를 조건부로만 건드린다) 때문이다.
-- 자바에서 HGET 으로 상태를 읽고 판단한 다음 HSET 하면 그 사이에 dispatch-engine 이 끼어든다.
--
--   t=0  geo-indexer 가 status 를 읽는다 → IDLE. "그대로 두면 되겠네"
--   t=1  dispatch-engine 이 이 라이더에게 제안을 보낸다 → status = OFFERED
--   t=2  geo-indexer 가... 아무것도 안 쓴다 (여기까진 괜찮다)
--
-- 문제는 OFFLINE 일 때다.
--
--   t=0  geo-indexer 가 status 를 읽는다 → OFFLINE. "온라인으로 올려야겠다"
--   t=1  dispatch-engine 이 제안을 보낸다 → status = OFFERED
--   t=2  geo-indexer 가 IDLE 을 쓴다 → 제안을 들고 있는 라이더가 "한가함" 이 된다
--   t=3  다른 주문이 이 라이더를 또 후보로 뽑는다
--
-- 읽기와 쓰기를 한 덩어리로 묶어야 t=1 이 끼어들 자리가 없어진다.
-- 제안 보드에 건 펜싱 규칙이나 release-rider.lua 와 같은 생각이다.
--
-- KEYS[1] = riders:online (GEO)
-- KEYS[2] = rider:state:{riderId} (Hash)
-- KEYS[3] = riders:heartbeat (ZSET)
-- ARGV[1] = riderId, ARGV[2] = lng, ARGV[3] = lat, ARGV[4] = lastSeenAt(epoch ms)
--
-- 반환 1 = 오프라인이던 사람을 온라인으로 올렸다, 0 = 좌표만 갱신했다
--
-- (키 세 개를 한 스크립트에서 만진다. 레디스 클러스터로 가면 같은 슬롯에 있어야 해서
--  해시태그가 필요한데, 지금은 단일 인스턴스라 그냥 둔다)

local riderId = ARGV[1]
local lng     = ARGV[2]
local lat     = ARGV[3]
local seenAt  = ARGV[4]

-- GEOADD 는 경도가 먼저다. 순서를 바꾸면 엉뚱한 데 찍히는데 에러는 안 난다.
redis.call('GEOADD', KEYS[1], lng, lat, riderId)

-- GEO 만으로는 "좌표가 오래된 사람" 을 찾을 수 없어서 시각 인덱스를 따로 유지한다.
-- GI-02 오프라인 정리가 이걸 ZRANGEBYSCORE 로 훑는다.
redis.call('ZADD', KEYS[3], seenAt, riderId)

redis.call('HSET', KEYS[2], 'lat', lat, 'lng', lng, 'lastSeenAt', seenAt)

local status = redis.call('HGET', KEYS[2], 'status')

-- 처음 보는 라이더이거나 오프라인이던 사람이면 온라인으로 올린다.
-- 그 외에는 손대지 않는다 — IDLE 은 이미 맞고, OFFERED 나 DELIVERING 은 배차 쪽 소관이다.
-- 모르는 값이 들어 있어도 안 건드린다. 우리가 모르는 상태를 IDLE 로 덮는 것보다
-- 그냥 두는 쪽이 덜 위험하다 (후보 검색은 IDLE 만 뽑으니 안 뽑힐 뿐이다).
if status == false or status == 'OFFLINE' then
    redis.call('HSET', KEYS[2], 'status', 'IDLE', 'idleSince', seenAt)
    return 1
end

return 0
