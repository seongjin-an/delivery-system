"""판 전후 스크레이프의 차이로 offer_relay_delay 분포를 낸다.
   python3 histogram.py before.txt after.txt
구간 경계(le)마다 '이 시간 안에 도착한 비율' 을 찍는다. 백분위는 구간 안에서 직선으로 채운 어림값이다."""
import re, sys

def load(path):
    buckets, count, total = {}, 0.0, 0.0
    for line in open(path):
        m = re.match(r'offer_relay_delay_seconds_bucket\{.*le="([^"]+)".*\} (\S+)', line)
        if m:
            buckets[m.group(1)] = float(m.group(2))
        elif line.startswith('offer_relay_delay_seconds_count'):
            count = float(line.split()[-1])
        elif line.startswith('offer_relay_delay_seconds_sum'):
            total = float(line.split()[-1])
    return buckets, count, total

b0, c0, s0 = load(sys.argv[1])
b1, c1, s1 = load(sys.argv[2])
n = c1 - c0
if n <= 0:
    print("샘플 없음"); sys.exit()
les = sorted((le for le in b1 if le != '+Inf'), key=float)
print(f"샘플 {int(n)}건, 평균 {(s1 - s0) / n:.2f}초")
prev_le, prev_cum = 0.0, 0.0
cum = {}
for le in les:
    c = b1[le] - b0.get(le, 0.0)
    cum[float(le)] = c
    print(f"  {float(le):6.2f}초 안: {c / n * 100:6.2f}%  ({int(c)}건)")
print(f"  그 뒤   : {(n - cum[float(les[-1])]) / n * 100:6.2f}%")
for p in (0.5, 0.95, 0.99):
    target = n * p
    lo_le, lo_c = 0.0, 0.0
    for le in sorted(cum):
        if cum[le] >= target:
            est = lo_le + (le - lo_le) * ((target - lo_c) / max(cum[le] - lo_c, 1e-9))
            print(f"  p{int(p*100)} ≈ {est:.2f}초 ({lo_le:.2f}~{le:.2f} 구간)")
            break
        lo_le, lo_c = le, cum[le]
    else:
        print(f"  p{int(p*100)} > {les[-1]}초")
