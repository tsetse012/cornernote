package ai.pleos.coach.core;

/**
 * 조향각 → 조향율 δ̇. 창 1.28초 (7.8Hz 에서 10샘플), polyorder=1.
 *
 * <p>1초 미만 창은 1° 양자화 잡음이 ±7.8 °/s 로 커져서 쓰지 않는다.
 *
 * <p><b>주의</b>: Pleos 조향 콜백은 <b>값이 변할 때만</b> 발화한다. 그래서 "샘플 10개"를
 * 세면 조향이 잠시 멈춘 동안 창이 몇 초로 늘어나 기울기가 희석된다. 창은 <b>시간</b>으로
 * 자르고, 그 안에 샘플이 3개 이상일 때만 유효로 본다.
 */
public final class SteerRateEstimator {

    public static final double SAMPLE_HZ = 7.8;
    /** 창 길이(초). 7.8Hz 에서 10샘플. */
    public static final double WINDOW_SEC = 1.28;
    public static final int WINDOW = 10;
    /**
     * 유효 판정에 필요한 최소 샘플 수. 2개면 기울기가 정의된다.
     * (실차는 7.8Hz 라 10개가 차지만, 에뮬레이터 콘솔은 1Hz 로 조향을 주므로 2개까지 허용한다)
     */
    public static final int MIN_SAMPLES = 2;
    /** 기울기를 믿으려면 창이 최소 이만큼은 벌어져야 한다. 1초 미만은 1° 양자화 잡음이 ±7.8°/s. */
    public static final double MIN_SPAN_SEC = 0.90;
    /**
     * 콜백이 "값이 변할 때만" 오므로, 조향이 한 번 딱 바뀌고 그대로면 창 안에 샘플이 1개만 남는다.
     * 그때는 마지막 두 샘플(= 마지막 변화의 양 끝)로 기울기를 낸다. 단 간격이 이보다 벌어지면
     * 변화 시점을 특정할 수 없으므로 포기한다.
     */
    public static final double MAX_EDGE_SPAN_SEC = 2.5;
    public static final double DELAY_SEC = WINDOW_SEC / 2.0;

    private final Samples.Ring ring = new Samples.Ring(64);
    private double rateDegPerSec;
    private long centerMillis;
    private boolean valid;

    /** @param steerDeg 조향각(도). 부호는 차량 신호 그대로. */
    public void add(long elapsedMillis, double steerDeg) {
        ring.add(elapsedMillis, steerDeg);
        recompute(elapsedMillis);
    }

    /**
     * 시간 창 안의 샘플로 기울기를 다시 계산한다.
     *
     * @param nowMillis 현재 시각. 마지막 조향 변화가 창 길이보다 오래되면 "변화 없음"으로 본다.
     */
    public void recompute(long nowMillis) {
        int n = ring.size();
        if (n < 2) {
            valid = false;
            rateDegPerSec = 0.0;
            return;
        }
        long newest = ring.latestTime();
        if (nowMillis - newest > (long) Math.round(WINDOW_SEC * 1000.0)) {
            // 조향이 창 길이 넘게 그대로 → 조향율 0
            valid = false;
            rateDegPerSec = 0.0;
            centerMillis = nowMillis;
            return;
        }
        long cutoff = newest - (long) Math.round(WINDOW_SEC * 1000.0);
        int from = n - 1;
        while (from > 0 && ring.timeAt(from - 1) >= cutoff) {
            from--;
        }
        int count = n - from;
        long span = ring.latestTime() - ring.timeAt(from);
        if (count < MIN_SAMPLES) {
            // 창 안 샘플이 1개 = 조향이 한 번 바뀌고 그대로. 마지막 두 샘플(변화의 양 끝)로 낸다.
            if (n < 2) {
                valid = false;
                rateDegPerSec = 0.0;
                centerMillis = newest;
                return;
            }
            long edgeSpan = ring.latestTime() - ring.timeAt(n - 2);
            if (edgeSpan <= 0L || edgeSpan > (long) (MAX_EDGE_SPAN_SEC * 1000.0)) {
                valid = false;
                rateDegPerSec = 0.0;
                centerMillis = newest;
                return;
            }
            rateDegPerSec = SgDerivative.slopeFromRing(ring, n - 2, n - 1);
            centerMillis = (ring.latestTime() + ring.timeAt(n - 2)) / 2L;
            valid = true;
            return;
        }
        if (span < (long) (MIN_SPAN_SEC * 1000.0)) {
            valid = false;
            rateDegPerSec = 0.0;
            centerMillis = newest;
            return;
        }
        rateDegPerSec = SgDerivative.slopeFromRing(ring, from, n - 1);
        centerMillis = (ring.latestTime() + ring.timeAt(from)) / 2L;
        valid = true;
    }

    public boolean isValid() {
        return valid;
    }

    public double rateDegPerSec() {
        return rateDegPerSec;
    }

    public long centerMillis() {
        return centerMillis;
    }

    public void reset() {
        ring.clear();
        rateDegPerSec = 0.0;
        centerMillis = 0L;
        valid = false;
    }
}
