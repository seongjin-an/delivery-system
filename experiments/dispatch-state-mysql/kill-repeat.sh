#!/usr/bin/env bash
# dispatch-engine kill -9 판을 두 번 더 돈다. 죽이는 순간 수락 도중인 요청이 걸리는지는 운이라 한 판으로는 약하다.
#   DISPATCH_STATE_STORE=redis|mysql ./kill-repeat.sh <접두어>
cd "$(dirname "$0")"
for n in 2 3; do KILL=dispatch RATE=20 ./load.sh $1-kill-dispatch-$n; done
echo "REPEAT DONE $1"
