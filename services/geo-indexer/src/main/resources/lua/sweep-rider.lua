-- GI-02 라이더 한 명을 오프라인으로 정리한다. 기능 정의서 GI-02 규칙 2번.
--
-- 자바에서 ZRANGEBYSCORE 로 대상을 뽑은 뒤 한 명씩 이걸 돌린다. 판정을 전부 여기서 다시 하는 이유는
-- 뽑은 시점과 처리하는 시점 사이에 두 가지가 끼어들 수 있어서다.
--
--   t=0  스위퍼가 뽑는다 → R 은 31초째 조용하다
--   t=1  R 의 새 좌표가 들어온다 → GI-01 이 heartbeat 를 지금으로 바꾼다
--   t=2  스위퍼가 R 을 OFFLINE 으로 만든다
--
-- 좌표를 잘 보내고 있는 사람이 지도에서 빠진다. 3초 뒤 다음 좌표로 GI-01 이 IDLE 로 되살리긴 하는데
-- 그때 idleSince 가 새로 찍혀서, 20분 기다린 라이더가 대기 보너스를 통째로 잃는다.
-- status 도 마찬가지로 t=0 과 t=2 사이에 dispatch-engine 이 OFFERED 로 바꿀 수 있다.
--
-- KEYS[1] = riders:online (GEO)
-- KEYS[2] = rider:state:{riderId} (Hash)
-- KEYS[3] = riders:heartbeat (ZSET)
-- ARGV[1] = riderId, ARGV[2] = cutoff(epoch ms). 이보다 오래 조용했으면 정리 대상
--
-- 반환값은 SweepOutcome 의 code 와 맞춘다.
--   0 = 이미 heartbeat 에 없다 (다른 인스턴스가 먼저 치웠다)
--   1 = 그 사이 새 좌표가 왔다. 손 안 댄다
--   2 = DELIVERING. 아무것도 안 한다
--   3 = OFFERED. GEO 에서만 뺐다
--   4 = OFFLINE 으로 만들었다
--   5 = 모르는 status. GEO 에서만 뺐다

local riderId = ARGV[1]
local cutoff  = tonumber(ARGV[2])

local score = redis.call('ZSCORE', KEYS[3], riderId)
if not score then
    return 0
end
if tonumber(score) > cutoff then
    return 1
end

local status = redis.call('HGET', KEYS[2], 'status')

-- 지하 주차장에 들어간 배달 중 라이더를 오프라인으로 만들면 진행 중인 주문이 붕 뜬다.
-- heartbeat 에서도 안 뺀다. 배달이 끝나서 IDLE 이 됐는데 여전히 조용하면, 다음 주기에 여기서 다시 잡아야 한다.
if status == 'DELIVERING' then
    return 2
end

-- 제안은 10초 뒤 만료되면서 RE-02 가 IDLE 로 돌려놓는다. 그다음 주기에 IDLE 로 다시 걸려서 정리된다.
-- 여기서 OFFLINE 을 써버리면 안 된다. 터널을 빠져나온 라이더의 좌표가 1초 뒤에 들어오면 GI-01 이
-- "오프라인이던 사람이네" 하고 IDLE 로 올려버린다. 제안이 아직 살아 있는데 한가한 사람이 되는 거다.
-- 그래서 GEO 에서만 빼서 새 후보로 안 뽑히게만 한다.
if status == 'OFFERED' then
    redis.call('ZREM', KEYS[1], riderId)
    return 3
end

if status == false or status == 'IDLE' or status == 'OFFLINE' then
    redis.call('ZREM', KEYS[1], riderId)
    -- heartbeat 에서도 뺀다. 안 빼면 오프라인 라이더가 10초마다 계속 다시 뽑힌다.
    -- 다시 좌표를 보내면 GI-01 이 ZADD 로 넣어준다.
    redis.call('ZREM', KEYS[3], riderId)
    -- 해시가 아예 없으면 HSET 이 status 하나만 든 해시를 새로 만든다. 그건 안 한다.
    if status ~= false then
        redis.call('HSET', KEYS[2], 'status', 'OFFLINE')
    end
    return 4
end

-- 우리가 모르는 값. GI-01 과 같은 생각으로 status 는 안 덮는다. 오래된 좌표로 후보에 뜨는 것만 막는다.
-- heartbeat 에 남겨두는 건 일부러다. 주기마다 unknown 지표가 계속 올라가야 누가 이상한 값을 썼는지 알아챈다.
redis.call('ZREM', KEYS[1], riderId)
return 5
