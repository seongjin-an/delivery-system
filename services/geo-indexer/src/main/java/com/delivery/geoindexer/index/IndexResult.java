package com.delivery.geoindexer.index;

/**
 * 배치 하나를 처리한 결과.
 *
 * @param received  카프카에서 받은 레코드 수
 * @param indexed   실제로 레디스에 쓴 라이더 수 (중복을 걷어낸 뒤)
 * @param cameOnline 오프라인이던 상태에서 온라인으로 올라간 라이더 수
 */
public record IndexResult(int received, int indexed, int cameOnline) {

    /**
     * 한 배치에 같은 라이더가 몇 번이나 들어왔는지.
     *
     * <p>이 값이 커지면 컨슈머가 밀리고 있다는 뜻이다. 정상이면 라이더가 3초마다 한 점을
     * 보내니까 한 배치에 같은 사람이 두 번 들어올 일이 거의 없다. 5초어치가 밀려서 한꺼번에
     * 들어오기 시작하면 그때부터 중복이 쌓인다. 시나리오 B 에서 볼 숫자다.
     */
    public int deduped() {
        return received - indexed;
    }
}
