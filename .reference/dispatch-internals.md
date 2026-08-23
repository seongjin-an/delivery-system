# 배차 엔진 내부 — dispatch-engine 과 레디스

주문 하나가 들어와서 배차 제안이 나갈 때까지 dispatch-engine 이 레디스와 무슨 대화를 하는지
명령 단위로 정리한 문서다. 1단계에서 dispatch-engine 을 짤 때 이걸 보고 짜면 된다.

흐름 전체는 [`flow-scenarios.md`](flow-scenarios.md), 확장 실험은
[`scaling-scenarios.md`](scaling-scenarios.md) 에 있다.

## 이름 규칙부터

키 이름이 비슷하게 생겨서 헷갈리기 쉬운데, 구분자로 갈라 보면 된다.

- 콜론으로 끊으면 레디스 키다. `dispatch:offer:order-77`
- 점으로 끊으면 카프카 토픽이거나 래빗엠큐 라우팅 키다. `order.created`, `offer.created`

코드 읽을 때 콜론이 보이면 레디스, 점이 보이면 브로커라고 생각하면 된다.

---

# 1. 배차 한 건에 레디스와 아홉 번 이야기한다

`order.created` 하나를 받아서 제안을 던질 때까지의 순서다.

| 순서 | 명령 | 목적 |
|---|---|---|
| 1 | `SET lock:dispatch:{orderId} {인스턴스ID} NX PX 15000` | 이 주문을 나만 처리하겠다고 찜한다 |
| 2 | `EXISTS dispatch:offer:{orderId}` | 전에 처리한 적 있는 주문인지 본다 |
| 3 | `GEOSEARCH riders:online FROMLONLAT {lng} {lat} BYRADIUS 3000 m ASC COUNT 30 WITHDIST` | 가게 근처 라이더 30명을 뽑는다 |
| 4 | 파이프라인으로 `HMGET rider:state:{id} status lastSeenAt` 30번 | 30명이 지금 한가한지 확인한다 |
| 5 | `RPUSH dispatch:candidates:{orderId} r1 r2 ...` + `EXPIRE 600` | 점수순 상위 10명을 줄 세워 저장한다 |
| 6 | `LPOP dispatch:candidates:{orderId}` | 1순위를 꺼낸다 |
| 7 | `SET lock:rider:{riderId} {orderId} NX PX 12000` | 이 라이더를 찜한다 |
| 8 | `HSET dispatch:offer:{orderId} ...` + `EXPIRE 600` | 제안 상태를 기록한다 |
| 9 | Lua 로 `lock:dispatch:{orderId}` 해제 | 내가 잡은 락인지 확인하고 푼다 |

파이프라인으로 묶어서 아홉 번이다. 4번을 for 루프로 돌리면 서른여덟 번이 된다.

초당 200건을 배차하면 왕복이 초당 1800번이고, 여기에 geo-indexer 가 보내는 `GEOADD` 가
초당 1000번씩 더 들어온다. 확장 시나리오 D 에서 레디스가 병목이 되는 게 이 숫자 때문이다.

---

# 2. GEOSEARCH 안에서 벌어지는 일

레디스 GEO 는 별도 자료구조가 아니다. **그냥 sorted set 이다.**
위도와 경도를 geohash 라는 52비트 정수 하나로 접어서 그걸 score 로 넣어둔 것이다.

geohash 는 지구를 격자로 자르고 각 칸에 번호를 매긴 것인데, **가까운 칸끼리 번호도 비슷해지도록**
매긴 게 핵심이다. 강남이 12345 면 역삼은 12346 쯤 되는 식이다. 그래서 "강남 반경 3km" 를
"score 가 12340 에서 12350 사이" 라는 범위 조회로 바꿔서 풀 수 있다.

`GEOSEARCH` 가 하는 일이 그거다. 반경에 걸치는 격자 칸을 아홉 개쯤 계산하고, 각 칸을
`ZRANGEBYSCORE` 로 긁어와서, 긁어온 것들의 실제 거리를 하나씩 재서 3km 안에 드는 것만 남긴다.

## COUNT 와 ASC 를 같이 줘야 한다

반경 안에 라이더가 500명 있으면 500명을 다 훑는다. 강남 점심시간이 그렇다.

`COUNT 30` 과 `ASC` 를 같이 주면 레디스가 가까운 순으로 30명을 채우는 즉시 멈출 수 있다.
`COUNT` 를 안 주면 500명을 다 계산해서 다 네트워크로 실어 보낸다. 우리는 10명만 쓸 건데도 그렇다.

## GEO 키가 하나라는 것

`riders:online` 하나에 전국 라이더가 다 들어간다. 레디스 클러스터로 가면 이 키가 한 슬롯에
몰리고, 그 노드가 핫스팟이 된다. 나중에 `riders:online:{zoneId}` 로 쪼개는 게 확장 시나리오 D 의
연장선이다.

## sorted set 멤버에는 TTL 을 걸 수 없다

`riders:online` 에 라이더를 넣을 때 "30초 뒤에 알아서 빠져라" 를 못 한다. TTL 은 키 단위로만 걸린다.
그래서 geo-indexer 에 스케줄러를 두고 `ZREM` 으로 직접 빼야 한다. 흐름 시나리오 10번이 그 얘기다.

---

# 3. 30명을 뽑아서 10명으로 줄이는 이유

`riders:online` 에는 좌표밖에 없다. 그 라이더가 지금 배달 중인지, 방금 제안을 거절했는지,
다른 주문에 이미 찜해졌는지는 GEO 가 모른다. 그건 `rider:state:{id}` 해시에 따로 있다.

```
GEOSEARCH 로 거리순 30명           ← 거리만 아는 단계
  ↓
30명의 rider:state 를 한꺼번에 조회   ← 상태를 붙이는 단계
  ↓
status == IDLE 인 사람만 남긴다
점수 = 거리 + 대기시간 으로 정렬
  ↓
상위 10명을 후보로 확정
```

30명을 뽑는 건 배달 중인 사람이 걸러질 걸 감안한 여유분이다. 점심시간에는 절반 이상이
배달 중이라 30명 중 12명만 남기도 한다. 이 숫자는 부하를 걸어보고 조정할 값이다.

---

# 4. 여기 N+1 함정이 숨어 있다

4번 단계를 이렇게 짜고 싶어진다.

```java
for (String riderId : candidates) {          // 30명
    var state = redis.opsForHash().entries("rider:state:" + riderId);
    // ...
}
```

로컬에서는 문제가 안 보인다. 레디스가 같은 머신에 있으니 한 번에 0.1ms 고 30번이면 3ms 다.
그런데 실제 환경에서 레디스가 다른 노드에 있으면 왕복 한 번이 1ms 쯤 된다. 30번이면 30ms 다.
**배차 하나에 30ms 를 상태 조회에만 쓰는 셈이다.**

파이프라인으로 묶으면 왕복 한 번이다.

```java
List<Object> states = redis.executePipelined((RedisCallback<Object>) conn -> {
    for (String riderId : candidates) {
        conn.hashCommands().hMGet(key(riderId), FIELD_STATUS, FIELD_LAST_SEEN);
    }
    return null;   // 파이프라인은 반환값을 여기서 안 쓴다
});
```

파이프라인은 편지 서른 통을 한 봉투에 넣어 보내는 것이다. 우체국을 서른 번 왕복하는 대신
한 번 간다. 명령들이 원자적으로 실행되는 건 아니라서 사이에 다른 클라이언트 명령이 끼어들 수
있는데, 여기서는 상태를 읽기만 하니 상관없다.

---

# 5. 락이 두 개인데 역할이 다르다

## `lock:dispatch:{orderId}` — 주문을 지킨다

같은 주문을 두 번 배차하는 걸 막는다.

카프카는 at-least-once 라서 같은 `order.created` 가 두 번 올 수 있다. 컨슈머가 처리를 끝내고
오프셋을 커밋하기 직전에 죽으면, 리밸런싱 뒤에 다른 인스턴스가 그 메시지를 다시 받는다.
둘 다 배차를 진행하면 주문 하나에 라이더 두 명이 배차된다.

```
SET lock:dispatch:order-77 "dispatch-engine-2:1787299477" NX PX 15000
```

값에 인스턴스 이름을 넣는 게 포인트다. 락을 풀 때 "이게 내가 잡은 락인가" 를 확인해야 한다.

생명주기가 아주 짧다. `order.created` 를 받는 순간 잡고, 제안 발행이 끝나면 바로 푼다.
정상이면 100ms 안쪽이다. TTL 15초는 "프로세스가 죽어도 15초 뒤에는 알아서 풀려라" 라는
안전장치일 뿐이고, 정상 경로에서 15초를 쓰는 게 아니다.

**이 락은 "지금 누가 처리 중인가" 만 알려준다.** "전에 처리한 적 있나" 는 못 알려준다.
15초 뒤에 사라지니까. 중복 메시지가 20초 뒤에 도착하면 락은 이미 없어서 그냥 통과한다.
그래서 `EXISTS dispatch:offer:{orderId}` 가 따로 필요하다.

회의실 문에 걸어두는 "사용 중" 표찰이다. 지금 쓰는 중인지는 알려주지만 어제 누가 썼는지는
안 알려준다.

## `lock:rider:{riderId}` — 라이더를 찜한다

라이더 한 명에게 서로 다른 주문 두 개가 동시에 제안되는 걸 막는다.

강남에 한가한 라이더가 한 명인데 주문이 두 건 들어온 상황이다. 주문 두 개는 파티션이 달라서
dispatch-engine 인스턴스 두 대가 각각 처리한다. 둘 다 그 라이더를 1순위로 뽑는다.
락이 없으면 라이더 폰에 제안 두 개가 동시에 뜨고, 둘 다 수락하면 배달 두 개를 동시에 맡는다.

```
SET lock:rider:R1 "order-77" NX PX 12000
```

**앞의 락과 결정적으로 다른 게 값이다.** 여기는 인스턴스 이름이 아니라 **찜한 주문 ID** 가 들어간다.
주인이 프로세스가 아니라 주문이기 때문이다. 그래서 offer-relay 가 재제안할 때 이 락을 풀어도
되는데, 그때도 값이 자기 주문 ID 인지 확인하고 풀어야 한다. 다른 주문이 이미 그 라이더를
채갔을 수도 있다.

TTL 12초는 제안 10초에 2초를 얹은 값이다. 락이 먼저 풀리면 제안이 아직 살아 있는데 다른 주문이
그 라이더를 또 잡아간다. 반대로 너무 길게 잡으면 거절한 라이더가 한동안 논다.

## 수락된 다음에는 락이 라이더를 못 지킨다

여기가 제일 중요하다. 배달은 30분 걸리는데 TTL 12초짜리 락으로 30분을 덮을 수 없다.
TTL 을 30분으로 늘리는 것도 답이 아니다. 배달이 20분에 끝나면 10분을 헛되게 잠가두고,
40분 걸리면 중간에 풀린다.

그래서 역할을 이렇게 나눈다.

| 구간 | 누가 라이더를 지키나 |
|---|---|
| 제안 발행 ~ 수락 전 (10초) | `lock:rider:{riderId}` |
| 수락 ~ 배달 완료 (30분) | `rider:state:{riderId}` 의 `status = DELIVERING` |

**락은 "제안 중" 만 지키고, "배달 중" 은 상태 필드가 지킨다.**
그래서 후보를 뽑을 때 `SET NX` 하나만 믿으면 안 되고 `status` 도 같이 봐야 한다.
3번의 "상태를 붙이는 단계" 가 여기서 필요해진다.

## 락 해제는 Lua 로

값 없이 `SET key 1 NX PX` 로 해두면 이런 사고가 난다. A 가 락을 잡고 일하는데 느려져서 15초를
넘겼다. TTL 이 지나 락이 풀리고 B 가 새로 잡았다. 뒤늦게 끝난 A 가 마무리로 `DEL` 을 한다.
**B 가 잡은 락을 A 가 지웠다.** 그러면 C 가 또 들어와서 B 와 같이 같은 주문을 배차한다.

```lua
-- KEYS[1] = 락 키, ARGV[1] = 내 소유자 이름
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
```

화장실 문에 자기 이름표를 걸어두고, 나올 때 이름표가 자기 것인지 확인하고 떼는 것이다.
남의 이름표가 걸려 있으면 안 건드리고 그냥 나온다.

---

# 6. TTL 은 예외 없이 붙인다

`dispatch:candidates` 와 `dispatch:offer` 는 배차가 끝나면 지우는 게 맞다. 그런데 지우는 코드가
안 도는 경로가 너무 많다. 프로세스가 죽거나, 예외가 터지거나, 래빗엠큐 발행이 실패하거나.

TTL 을 안 걸면 못 지운 키가 영구히 남는다. 주문 하나당 키 두 개니까 하루 10만 주문이면
20만 개가 쌓이고, 그게 매일 늘어난다. 몇 달 뒤에 "레디스 메모리가 왜 이렇게 늘었지" 하면서
원인을 찾게 된다.

그래서 `RPUSH` 다음에 무조건 `EXPIRE` 를 붙인다. 값은 배차가 최대로 걸릴 시간
(5회 × 10초 = 50초) 보다 넉넉하게 10분쯤 준다. 정상 경로에서는 우리가 지우고, 비정상 경로에서는
TTL 이 치우는 이중 구조다.

---

# 7. `dispatch:offer:{orderId}` — 제안 현황판

이건 락이 아니다. 데이터다.

```
HSET dispatch:offer:order-77
  offerId    of-abc123
  riderId    R1
  state      OFFERED        # OFFERED | ACCEPTED | EXPIRED | FAILED
  attempt    1
  offeredAt  1787299477
EXPIRE dispatch:offer:order-77 600
```

수락과 만료 중에 어느 쪽이 이겼는지 판정하는 **단 하나의 기준**이다.

**왜 공유 저장소에 있어야 하나.** 두 서비스가 같은 걸 보고 판단해야 한다. 라이더의 수락 요청은
dispatch-engine 이 받고, 10초 뒤 만료 메시지는 offer-relay 가 받는다. 서로 다른 프로세스다.
각자 자기 메모리에 상태를 들고 있으면 서로 뭘 아는지 모른다.

**이게 없으면 무슨 일이 나나.** 라이더가 9초에 수락했는데 offer-relay 가 그걸 모르고 10초에
2순위에게 또 제안한다. 2순위도 수락하면 **라이더 두 명이 같은 주문을 가지고 가게에 나타난다.**
한 명은 헛걸음이고 클레임이 들어온다.

**부가 역할.** 5번에서 말한 "전에 처리한 적 있나" 를 이 키의 존재 여부로 알 수 있다.
락은 15초 뒤에 사라지지만 이건 10분 남아 있으니, 늦게 도착한 중복 메시지를 이걸로 걸러낸다.

화이트보드에 적어둔 현황판이다. 두 사람이 각자 수첩에 적으면 서로 뭘 아는지 모르는데,
벽에 걸린 보드 하나를 같이 보면 그럴 일이 없다.

---

# 8. 수락 처리는 Lua CAS 로

이렇게 짜면 버그다.

```java
String state = redis.opsForHash().get(offerKey, "state");   // 읽고
if ("OFFERED".equals(state)) {                              // 비교하고
    redis.opsForHash().put(offerKey, "state", "ACCEPTED");   // 쓴다
}
```

"레디스는 싱글 스레드니까 안전하지 않나" 가 여기서 제일 많이 하는 오해다.
**싱글 스레드가 보장하는 건 명령 하나가 쪼개지지 않는다는 것뿐이다.**
명령 세 개 사이에는 다른 클라이언트의 명령이 얼마든지 끼어든다.

은행 창구가 하나뿐이라도, 잔액을 확인하고 밖에 나갔다가 다시 와서 출금하면 그 사이에
다른 사람이 돈을 빼갈 수 있다. 창구가 하나인 게 내 두 번의 방문 사이를 지켜주지는 않는다.

```lua
-- KEYS[1] = dispatch:offer:{orderId}
-- ARGV[1] = riderId, ARGV[2] = 수락 시각
local state = redis.call('HGET', KEYS[1], 'state')
if state == false     then return -2 end   -- 제안이 사라졌다 (TTL 만료)
if state ~= 'OFFERED' then return -1 end   -- 이미 수락됐거나 만료됐다
if redis.call('HGET', KEYS[1], 'riderId') ~= ARGV[1] then return 0 end   -- 남의 제안이다
redis.call('HSET', KEYS[1], 'state', 'ACCEPTED', 'acceptedAt', ARGV[2])
return 1
```

반환값을 갈라두면 API 응답을 다르게 줄 수 있다.

| 반환값 | 응답 | 라이더에게 보이는 말 |
|---|---|---|
| `1` | 200 | 배차 완료 |
| `-1` | 409 | 이미 다른 분이 받았어요 |
| `-2` | 410 | 제안이 만료됐어요 |
| `0` | 403 | 이 제안은 당신 것이 아니에요 |

실패를 하나로 뭉치면 라이더가 왜 안 되는지 알 수 없다.

`WATCH` 와 `MULTI` 로 낙관적 락을 쓰는 방법도 있는데, 충돌하면 재시도 루프를 직접 돌려야 하고
Spring Data Redis 에서 `SessionCallback` 으로 감싸는 게 번거롭다. Lua 는 왕복도 한 번이고
재시도도 필요 없어서 이 경우엔 Lua 가 낫다. Redisson 을 쓰면 이런 걸 다 감싸주는데,
직접 써봐야 뭘 감싸주는지 알게 된다.

---

# 9. `offer.created` — 라우팅 키

앞의 것들과 종류가 다르다. 레디스에 남는 상태가 아니고 래빗엠큐를 흘러가는 메시지에 붙는
**주소 라벨**이다.

라우팅 키는 익스체인지에게 "이 메시지를 어느 큐로 넣어라" 를 알려주는 값이다.
우체국에 편지를 낼 때 쓰는 우편번호와 같다. 편지를 우체국(익스체인지)에 내면서 우편번호를
적어주면, 우체국이 그 번호에 해당하는 곳(큐)으로 배달한다.

```
dispatch-engine 또는 offer-relay
  → dispatch.x 익스체인지에 발행, 라우팅 키 = "offer.created"
      ├→ dispatch.offer.notify   (notification-worker 가 즉시 꺼내서 푸시 발송)
      └→ dispatch.offer.timer    (아무도 안 꺼낸다. 10초를 센다)
```

**발행 한 번에 메시지가 두 개로 복제된다.** 같은 키에 큐 두 개가 묶여 있어서 그렇다.

**왜 두 번 발행하지 않고 브로커가 복제하게 하나.** 코드에서 알림 큐에 한 번, 타이머 큐에
한 번 발행하면 한쪽만 성공하는 경우가 생긴다. 그게 둘 다 사고다.

- 알림만 갔고 타이머가 없으면, 라이더가 안 받았을 때 아무도 모르고 그 주문은 방치된다.
- 타이머만 있고 알림이 안 갔으면, 라이더는 제안이 온 줄도 모르는데 10초 뒤에 다음 사람으로
  넘어간다. 그 라이더는 거절률만 올라간다.

발행 한 번으로 만들면 이 갈림길 자체가 없어진다. 아웃박스 패턴과 같은 생각이다.
둘로 갈릴 수 있는 지점을 하나로 합쳐버린다.

**메시지 안에 든 것.** `libs/common` 의 `DispatchOffer` 레코드다.
`offerId`, `orderId`, `riderId`, `attempt`, `offeredAt`.
여기에 OTel 에이전트가 헤더로 `traceparent` 를 붙여줘서 트레이스가 이어진다.

**만료되면 키가 바뀐다.** 타이머 큐에서 10초가 지나면 래빗엠큐가 그 메시지를 데드레터
익스체인지로 옮기는데, 이때 라우팅 키를 `offer.expired` 로 바꿔서 보낸다. 그래서
`dispatch.dlx` 에 묶인 `dispatch.offer.expired` 큐로 들어가고 offer-relay 가 꺼낸다.
같은 메시지가 라벨만 바꿔 달고 다른 목적지로 가는 것이다.

---

# 10. 네 가지를 한 표로

| | 어디 사나 | 누가 만드나 | 언제 사라지나 | 값에 뭐가 |
|---|---|---|---|---|
| `lock:dispatch:{orderId}` | 레디스 String | dispatch-engine | 제안 발행 끝나면 즉시, 아니면 15초 뒤 | 잡은 인스턴스 이름 |
| `lock:rider:{riderId}` | 레디스 String | dispatch-engine, offer-relay | 재제안할 때, 배달 완료할 때, 아니면 12초 뒤 | 찜한 주문 ID |
| `dispatch:offer:{orderId}` | 레디스 Hash | dispatch-engine | 배차 확정이나 실패 후 정리, 아니면 10분 뒤 | 제안 상태 전체 |
| `offer.created` | 래빗엠큐 라우팅 키 | dispatch-engine, offer-relay | 메시지가 소비되거나 TTL 로 만료되면 | 키 자체는 값이 아니라 주소 |

한 문장으로 구분하면, `lock:dispatch` 는 주문을 두 번 배차하지 않게 막고,
`lock:rider` 는 라이더에게 두 제안이 가지 않게 막고, `dispatch:offer` 는 수락과 만료 중 누가
이겼는지 판정하고, `offer.created` 는 그 제안을 알림 큐와 타이머 큐 양쪽에 실어 보낸다.

---

# 11. 이렇게 하면 깨지고, 이렇게 하면 안 깨진다

앞의 규칙들이 왜 필요한지 타임라인으로 확인한다. 등장인물은 이렇게 고정한다.

- 주문 `order-77`, `order-88`, `order-99`
- 라이더 `R1`, `R2`, `R3`
- dispatch-engine 인스턴스 `DE-1`, `DE-2`, `DE-3`

---

## 11.1 `lock:dispatch` 가 없으면

### 깨지는 경우 — 후보 목록이 두 배가 되고 제안이 두 개 나간다

```
t=0ms      DE-1  order.created(order-77) 소비
t=0ms      DE-2  order.created(order-77) 소비      ← 중복 메시지. 락이 없으니 둘 다 통과
t=5ms      DE-1  GEOSEARCH → [R1, R2, R3]
t=6ms      DE-2  GEOSEARCH → [R1, R2, R3]
t=8ms      DE-1  RPUSH dispatch:candidates:order-77 R1 R2 R3
t=9ms      DE-2  RPUSH dispatch:candidates:order-77 R1 R2 R3
                 → 리스트가 [R1, R2, R3, R1, R2, R3] 이 됐다
t=10ms     DE-1  LPOP → R1,  lock:rider:R1 획득,  R1 에게 제안
t=11ms     DE-2  LPOP → R2,  lock:rider:R2 획득,  R2 에게 제안
                 → 제안이 두 개 나갔다. R1 과 R2 폰에 동시에 뜬다
t=10,000ms 타이머 두 개가 동시에 만료 → offer-relay 가 두 번 재제안
                 LPOP → R3,  LPOP → R1
                 → 방금 거절한 R1 에게 또 제안이 간다
```

주문 하나에 제안이 네 번 나가고, 그중 하나는 방금 거절한 사람에게 다시 갔다.

### 안 깨지는 경우

```
t=0ms   DE-1  SET lock:dispatch:order-77 "DE-1:1787299477" NX PX 15000  → OK
t=0ms   DE-2  SET lock:dispatch:order-77 "DE-2:1787299477" NX PX 15000  → nil
t=1ms   DE-2  ack 만 하고 넘어간다. 로그에 "이미 처리 중이라 건너뜀" 한 줄
t=5ms   DE-1  GEOSEARCH → [R1, R2, R3], RPUSH, LPOP → R1, 제안 발행
t=12ms  DE-1  Lua 로 락 해제
```

---

## 11.2 락 값을 안 넣으면

### 깨지는 경우 — 남의 락을 지운다

```
t=0ms        DE-1  SET lock:dispatch:order-77 1 NX PX 15000  → OK
                   (값이 그냥 1이다. 누가 잡았는지 알 수 없다)
t=0~16,000   DE-1  GC 스톱더월드가 길게 걸리고 레디스도 느려져서 16초를 씀
t=15,000ms   레디스  TTL 만료로 락 자동 삭제
t=15,100ms   DE-2  중복 메시지 소비 → SET ... NX → OK  (락 획득)
t=15,105ms   DE-2  GEOSEARCH 시작
t=16,000ms   DE-1  드디어 끝남 → DEL lock:dispatch:order-77
                   ← DE-2 가 잡은 락을 DE-1 이 지웠다
t=16,050ms   DE-3  또 다른 중복 메시지 → SET ... NX → OK
                   ← 이제 DE-2 와 DE-3 가 같은 주문을 동시에 배차 중
```

락을 만들었는데도 11.1 사고가 그대로 재현된다. 더 나쁜 건 **로그를 봐도 원인이 안 보인다**는 점이다.
DE-2 입장에서는 락을 정상적으로 잡았는데 왜 중복이 났는지 알 수가 없다.

### 안 깨지는 경우

```
t=0ms       DE-1  SET lock:dispatch:order-77 "DE-1:1787299477" NX PX 15000 → OK
t=15,000ms  TTL 만료
t=15,100ms  DE-2  SET lock:dispatch:order-77 "DE-2:1787299492" NX PX 15000 → OK
t=16,000ms  DE-1  Lua 실행 → GET 이 "DE-2:1787299492" 다. 내 이름이 아니다 → 0 반환
                  락을 안 건드리고 나온다
```

여기서 하나 더 중요한 게 있다. **DE-1 은 반환값 0 을 보고 "내가 락을 잃었다"는 걸 알게 된다.**
그러면 그 뒤 작업도 취소해야 한다. 제안 발행을 아직 안 했으면 안 하고, 이미 했으면 보상 처리를 한다.
락 해제를 그냥 `DEL` 로 해버리면 락을 잃었다는 사실 자체를 모르고 지나간다.

---

## 11.3 `lock:rider` 가 없으면

상황: 강남에 한가한 라이더가 `R1` 한 명뿐인데 주문 두 건이 동시에 들어왔다.
`order-77` 은 파티션 2, `order-88` 은 파티션 5 라서 서로 다른 인스턴스가 처리한다.

### 깨지는 경우 — 라이더 한 명이 배달 두 개를 맡는다

```
t=0ms     DE-1  order-77 처리 시작, lock:dispatch:order-77 획득
t=0ms     DE-2  order-88 처리 시작, lock:dispatch:order-88 획득
                ← 키가 다르니 둘 다 성공한다. 여기까진 정상이다
t=5ms     DE-1  GEOSEARCH → [R1]
t=6ms     DE-2  GEOSEARCH → [R1]        ← 둘 다 R1 을 본다
t=10ms    DE-1  R1 에게 order-77 제안
t=11ms    DE-2  R1 에게 order-88 제안
                → R1 폰에 제안 두 개가 동시에 뜬다
t=3,000ms R1    돈 더 벌려고 둘 다 수락을 누른다
                order-77 → R1 확정,  order-88 → R1 확정
```

R1 이 강남역 김밥집과 역삼동 피자집을 동시에 가야 한다. 한쪽 음식은 확실히 식는다.

**`lock:dispatch` 는 이걸 못 막는다.** 키가 `order-77` 과 `order-88` 로 다르니 둘 다 락을 잡는 게
정상이다. 주문을 지키는 락과 라이더를 지키는 락이 따로 있어야 하는 이유가 이것이다.

### 안 깨지는 경우

```
t=10ms     DE-1  SET lock:rider:R1 "order-77" NX PX 12000  → OK
                 R1 에게 order-77 제안
t=11ms     DE-2  SET lock:rider:R1 "order-88" NX PX 12000  → nil
t=12ms     DE-2  R1 을 건너뛴다 → LPOP → 후보가 없다
                 → order-88 은 반경을 넓혀 재검색하거나 실패 처리
t=13,000ms R1    order-77 을 거절
t=13,005ms offer-relay  lock:rider:R1 해제
t=13,010ms       이제 order-88 이 R1 을 잡을 수 있다
```

R1 에게는 한 번에 하나씩만 간다. 거절하면 다음 주문이 순서를 이어받는다.

---

## 11.4 라이더 락을 값 확인 없이 풀면

### 깨지는 경우 — 방금 다른 주문이 잡은 찜을 풀어버린다

```
t=9,990ms   lock:rider:R1 = "order-77"  (TTL 이 곧 만료된다)
t=10,000ms  TTL 만료로 락이 사라졌다
t=10,002ms  DE-2  order-88 처리 → SET lock:rider:R1 "order-88" NX PX 12000 → OK
                  R1 에게 order-88 제안 발행
t=10,005ms  offer-relay  order-77 만료 메시지 처리 시작
t=10,006ms  offer-relay  DEL lock:rider:R1       ← 값 확인 없이 지운다
                         order-88 이 방금 잡은 찜이 사라졌다
t=10,010ms  DE-3  order-99 처리 → SET lock:rider:R1 "order-99" NX → OK
                  R1 에게 order-99 제안 발행
                  → R1 폰에 order-88 과 order-99 가 동시에 뜬다
```

11.3 사고가 다시 돌아왔다. 라이더 락을 열심히 만들었는데 푸는 쪽에서 무너뜨렸다.

### 안 깨지는 경우

```lua
-- KEYS[1] = lock:rider:R1, ARGV[1] = 내 주문 ID
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
```

```
t=10,006ms  offer-relay  EVALSHA release_rider  KEYS=lock:rider:R1  ARGV="order-77"
                         GET → "order-88" 이다. 내 주문이 아니다 → 0 반환
                         안 지우고 나온다
                         로그: "R1 찜이 이미 order-88 로 넘어갔다"
```

락을 **잡을 때만** 값을 확인하는 게 아니라 **풀 때도** 확인해야 한다.

---

## 11.5 `dispatch:offer` 가 없으면

제안 상태를 DE-1 메모리에만 들고 있는 경우다.

### 깨지는 경우 — 라이더 두 명이 같은 가게에 나타난다

```
t=0ms       DE-1  R1 에게 order-77 제안. 상태는 DE-1 힙에만 있다
t=9,200ms   R1    수락 → DE-1 이 dispatch.assigned 발행
                  R1 앱에 "배차됐어요! 강남역 김밥집으로 가세요"
t=10,000ms  타이머 만료 → offer-relay 가 만료 메시지를 받는다
t=10,005ms  offer-relay  수락됐는지 알 방법이 없다. DE-1 메모리를 볼 수 없으니까
                         → 무조건 재제안. LPOP → R2, R2 에게 order-77 제안
t=12,000ms  R2    수락 → dispatch.assigned 또 발행
t=12,001ms  order-api  같은 주문에 assigned 가 두 번 왔다. 나중 걸로 덮어쓴다 → R2
t=12,300ms  R1    김밥집 도착. 사장님이 "이미 다른 기사님이 가져갔는데요"
```

R1 은 앱에서 배차 확정을 보고 출발했는데 주문은 조용히 R2 에게 넘어갔다. 헛걸음한 R1 이
클레임을 넣고, 앱 로그를 봐도 R1 에게 보낸 200 응답이 그대로 남아 있어서 설명하기가 어렵다.

### 안 깨지는 경우

```
t=0ms       DE-1  HSET dispatch:offer:order-77 riderId R1 state OFFERED attempt 1
t=9,200ms   R1    수락 → Lua CAS: state 가 OFFERED 고 riderId 가 R1 → ACCEPTED (반환 1)
t=10,000ms  타이머 만료 → offer-relay
t=10,005ms  offer-relay  HGET dispatch:offer:order-77 state → "ACCEPTED"
                         → 재제안하지 않는다. ack 만 하고 버린다
```

R2 에게는 아무것도 안 간다. 서로 다른 프로세스가 벽에 걸린 같은 보드를 보기 때문이다.

---

## 11.6 Lua CAS 없이 `HGET` 후 `HSET` 하면

### 깨지는 경우 — 9,998ms 에 수락하고 10,000ms 에 만료된 경우

```
DE-1 (수락 처리)                      offer-relay (만료 처리)
──────────────────────────────       ──────────────────────────────
HGET state → "OFFERED"
                                     HGET state → "OFFERED"
                                     ← 둘 다 OFFERED 를 읽었다
if OFFERED  통과
                                     if OFFERED  통과
HSET state ACCEPTED
                                     HSET state EXPIRED
                                     ← 나중 쓰기가 이긴다
                                     LPOP → R2, R2 에게 재제안
R1 에게 200 "배차됐어요" 응답
                                     R2 도 수락 → 배차 확정
```

R1 은 200 을 받고 출발했는데 상태는 `EXPIRED` 고 주문은 R2 에게 갔다. 11.5 와 같은 사고인데
원인 찾기가 훨씬 어렵다. 코드에는 `if OFFERED` 검사가 분명히 있으니 코드만 봐서는 문제가 안 보인다.

### 안 깨지는 경우

```
t=9,998ms   DE-1        EVALSHA accept_offer  order-77 R1  → 반환 1
                        (레디스 안에서 읽고 비교하고 쓰기가 끝났다. 사이에 아무도 못 들어온다)
t=10,000ms  offer-relay EVALSHA expire_offer  order-77     → state 가 ACCEPTED → 반환 -1
                        재제안하지 않는다
```

순서가 뒤집혀도 안전하다.

```
t=9,998ms   offer-relay EVALSHA expire_offer  → OFFERED → EXPIRED, R2 에게 재제안 (반환 1)
t=10,000ms  DE-1        EVALSHA accept_offer  → state 가 EXPIRED → 반환 -1
                        R1 에게 410 "제안이 만료됐어요"
                        R1 은 출발하지 않는다. R2 만 간다
```

어느 쪽이 먼저 도착하든 배차는 정확히 한 명이다.

---

## 11.7 `offer.created` 를 두 번 발행하면

### 깨지는 경우 A — 알림은 갔고 타이머가 없다

```
t=0ms   DE-1  convertAndSend(dispatch.x, "offer.notify", offer)  → OK
t=1ms   DE-1  convertAndSend(dispatch.x, "offer.timer",  offer)  → 예외
              (그 순간 커넥션이 끊겼다)
t=2ms   DE-1  예외를 잡고 로그만 남긴다. 제안은 이미 나갔으니까
t=3ms   R1    폰에 "강남역 김밥집 배달 요청" 알림이 뜬다
t=???   R1    바빠서 안 누른다
              타이머가 없으니 만료 이벤트가 영원히 안 온다
              offer-relay 는 이 주문의 존재조차 모른다
```

order-77 은 `dispatch:offer` 에 `OFFERED` 로 10분간 남아 있다가 TTL 로 조용히 사라진다.
고객은 "배달 준비 중"만 보면서 30분을 기다리고 아무도 이 주문을 처리하지 않는다.
**알림 실패보다 나쁜 게 아무 일도 안 일어나는 것이다.** 에러 로그 한 줄 말고는 흔적이 없다.

### 깨지는 경우 B — 타이머는 있고 알림이 없다

```
t=0ms      DE-1  타이머 큐 발행 → OK
t=1ms      DE-1  알림 큐 발행 → 실패
t=3ms      R1    아무 알림도 못 받는다. 자기에게 제안이 왔다는 걸 모른다
t=10,000ms 타이머 만료 → offer-relay → "R1 이 거절했다" 고 판단
                 R1 의 거절 횟수 +1, R2 에게 재제안
```

R1 은 아무 잘못도 안 했는데 거절률이 올라간다. 이게 쌓이면 배차 점수가 내려가서 콜이 덜 온다.
R1 이 "왜 저한테 콜이 안 와요?" 라고 문의해도 원인 찾기가 거의 불가능하다.

### 안 깨지는 경우 — 한 번 발행하고 브로커가 복제한다

바인딩을 이렇게 걸어둔다.

```
dispatch.offer.notify  ←  dispatch.x   (라우팅 키: offer.created)
dispatch.offer.timer   ←  dispatch.x   (라우팅 키: offer.created)
```

발행은 한 번이다.

```
t=0ms      DE-1  convertAndSend(dispatch.x, "offer.created", offer)   ← 한 번만
                 래빗엠큐가 큐 두 개에 각각 사본을 넣는다
                 발행이 성공하면 둘 다 들어가고, 실패하면 둘 다 안 들어간다
t=1ms      notification-worker  알림 큐에서 꺼내 R1 에게 푸시
t=10,000ms 타이머 큐에서 TTL 만료 → DLX → offer-relay
```

**"둘 중 하나만 성공"이라는 상태가 아예 존재하지 않는다.**

발행 자체가 실패하면 publisher confirm 으로 확인해서, 실패했으면 `dispatch:offer` 를 지우고
카프카 메시지를 ack 하지 않는다. 그러면 재소비돼서 처음부터 다시 한다. 결과는 항상
"아무것도 안 나갔다" 또는 "둘 다 나갔다" 둘 중 하나다.

---

## 11.8 한 줄로 정리

| 빼먹으면 | 무슨 사고가 나나 |
|---|---|
| `lock:dispatch` | 후보 목록이 두 배로 쌓이고 제안이 두 개 나간다 |
| 락 값에 소유자 이름 | 남의 락을 지워서 락이 있는데도 중복이 난다 |
| `lock:rider` | 라이더 한 명이 배달 두 개를 동시에 맡는다 |
| 락 해제 시 값 확인 | 다른 주문이 방금 잡은 찜을 풀어버린다 |
| `dispatch:offer` | 수락한 걸 모르고 재제안해서 라이더 두 명이 가게에 온다 |
| Lua CAS | 수락과 만료가 겹칠 때 나중 쓰기가 이겨서 응답과 상태가 어긋난다 |
| 발행 한 번으로 두 큐 | 알림만 가면 주문이 방치되고, 타이머만 가면 라이더 거절률이 억울하게 오른다 |

---

# 12. 싱글 스레드라서 서로를 느리게 만든다

레디스가 명령을 하나씩 처리하니까, `GEOSEARCH` 가 강남 라이더 500명을 훑는 동안
**다른 모든 명령이 줄 서서 기다린다.** geo-indexer 가 보내는 `GEOADD` 도 기다린다.

그래서 이런 악순환이 생긴다.

```
점심시간에 배차 요청이 몰린다
  → GEOSEARCH 가 무거워진다
  → 상관없어 보이는 위치 갱신이 밀린다
  → GEO 에 오래된 좌표가 남는다
  → 이미 멀리 간 라이더가 1순위로 나온다
  → 거절이 늘고 재제안이 늘어난다
  → 배차 요청이 또 늘어난다
```

dispatch-engine 과 geo-indexer 는 코드상으로 아무 관계가 없는데 레디스 하나를 공유한다는
이유로 서로를 끌어내린다. 확장 시나리오 D 에서 인스턴스를 늘렸는데 처리량이 떨어지는 이유 중
하나가 이것이다.

대응은 세 방향이다. 셋 다 해보고 비교하는 게 실습거리로 좋다.

1. 읽기용 레플리카를 두고 `GEOSEARCH` 를 거기로 보낸다.
2. `riders:online` 을 `riders:online:{zoneId}` 로 쪼개서 한 번에 훑는 양을 줄인다.
3. `COUNT` 를 더 조여서 애초에 훑는 양을 줄인다.

---

# 13. 왕복 아홉 번을 한 번으로 줄일 수도 있다

2번부터 8번까지를 하나의 Lua 스크립트에 다 넣을 수 있다. Lua 안에서 `GEOSEARCH` 도
호출할 수 있으니 후보 검색부터 제안 기록까지 전부 서버에서 한 번에 끝낼 수 있다.

그러면 왕복이 한 번으로 줄고, **전체가 원자적으로 돌아서 락 자체가 필요 없어진다.**
매력적으로 들린다.

그런데 대가가 있다.

- Lua 가 100줄을 넘어가면 디버깅이 지옥이다. 스택 트레이스도 없고 로그도 못 남기고,
  점수 계산 로직을 Lua 로 다시 써야 한다.
- 스크립트가 도는 동안 레디스가 다른 명령을 아예 못 받는다. 무거운 스크립트는 그 자체로
  12번의 간섭 문제를 키운다.

그래서 순서는 이게 맞다. 처음에는 명령 여러 개로 읽기 쉽게 짜고, 부하를 걸어서 레디스 왕복이
진짜 병목인지 확인하고, 확인됐을 때만 뭉친다. 지금 미리 Lua 로 짜면 병목도 아닌 곳을
최적화하면서 읽기 어려운 코드만 남는다.

---

# 14. 구현하기 전에 정할 것

1. **후보를 30명 뽑는 게 맞나.** 점심시간에 배달 중 비율을 재보고 정한다.
2. **점수에 뭘 넣을까.** 지금은 거리와 대기시간만 생각했다. 평점이나 연속 거절 횟수도 후보인데
   넣을수록 `GEOSEARCH` 뒤 계산이 무거워진다.
3. **`dispatch:offer` 를 DB 에도 쓸까.** 레디스가 재시작되면 이게 날아가서 수락과 만료를
   가를 근거가 사라진다(흐름 시나리오 12번). 그런데 DB 쓰기를 넣으면 배차 경로가 느려진다.
4. **라이더 락 TTL 12초가 맞나.** 제안 TTL 을 15초나 30초로 바꾸면 이것도 같이 움직여야 한다.
5. **`lock:dispatch` 를 아예 없앨 수 있나.** 주문 파티션 키를 `zoneId` 로 바꾸면 한 존은
   한 인스턴스만 처리하니 락이 필요 없어진다. 대신 강남 존이 핫파티션이 된다.
