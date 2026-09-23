-- NW-02 전역 토큰 버킷. 인스턴스가 몇 대든 합쳐서 초당 rate 번까지만 통과시킨다.
--
-- 인스턴스마다 한도를 나눠 갖지 않는 이유(기능 정의서 NW-02 규칙 1번): 8대가 25씩 나눠 가지면
-- 한 대가 죽는 순간 전체 한도가 175 로 줄고, 9대로 늘리면 225 가 돼서 외부 API 한테 429 를 맞는다.
--
-- 시각은 레디스 서버의 TIME 으로 잰다. 인스턴스마다 시계가 조금씩 다른데 각자 자기 시계로 토큰을 채우면,
-- 시계가 200ms 앞선 인스턴스가 올 때마다 토큰이 40개씩 더 생긴다.
--
-- KEYS[1] = rate:push (Hash: tokens, ts)
-- ARGV[1] = 초당 한도. 버킷 크기도 같은 값으로 둔다(1초치까지 몰아 쓸 수 있다)
--
-- 반환 1 = 통과, 0 = 토큰 없음

local rate = tonumber(ARGV[1])
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(bucket[1])
local ts = tonumber(bucket[2])
if tokens == nil or ts == nil then
    tokens = rate
    ts = now
end

tokens = math.min(rate, tokens + math.max(0, now - ts) * rate / 1000)

local allowed = 0
if tokens >= 1 then
    tokens = tokens - 1
    allowed = 1
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
-- 아무도 안 쓰면 1분 뒤 사라진다. 다시 오면 가득 찬 버킷으로 시작한다
redis.call('PEXPIRE', KEYS[1], 60000)
return allowed
