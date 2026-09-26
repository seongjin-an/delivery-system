"""판 전후 스크레이프의 차이로 한 타이머의 분포를 낸다.
   python3 hist.py <지표이름> before.txt after.txt
구간 경계(le)마다 '이 시간 안에 끝난 비율' 을 보고, 백분위는 구간 안에서 직선으로 채운 어림값이다."""
import re, sys

name = sys.argv[1]

def load(path):
    buckets, count, total = {}, 0.0, 0.0
    for line in open(path):
        m = re.match(name + r'_seconds_bucket\{.*le="([^"]+)".*\} (\S+)', line)
        if m:
            buckets[m.group(1)] = float(m.group(2))
        elif line.startswith(name + '_seconds_count'):
            count = float(line.split()[-1])
        elif line.startswith(name + '_seconds_sum'):
            total = float(line.split()[-1])
    return buckets, count, total

b0, c0, s0 = load(sys.argv[2])
b1, c1, s1 = load(sys.argv[3])
n = c1 - c0
if n <= 0:
    print(f"{name}: 샘플 없음"); sys.exit()
cum = {float(le): b1[le] - b0.get(le, 0.0) for le in b1 if le != '+Inf'}
out = [f"{name}: {int(n)}건, 평균 {(s1 - s0) / n * 1000:.1f}ms"]
for p in (0.5, 0.95, 0.99):
    target, lo_le, lo_c = n * p, 0.0, 0.0
    for le in sorted(cum):
        if cum[le] >= target:
            est = lo_le + (le - lo_le) * ((target - lo_c) / max(cum[le] - lo_c, 1e-9))
            out.append(f"p{int(p*100)}≈{est*1000:.1f}ms")
            break
        lo_le, lo_c = le, cum[le]
    else:
        out.append(f"p{int(p*100)}>5s")
print("  ".join(out))
