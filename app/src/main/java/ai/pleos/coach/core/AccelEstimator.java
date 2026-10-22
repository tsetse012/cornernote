package ai.pleos.coach.core;

/**
 * 속도 → 가속도. 창 45샘플 @45.6Hz (= 1.0초), polyorder=1.
 *
 * <p>중심창이므로 추정값은 <b>0.48초 지연</b>된다. 실시간 표시에 그대로 쓰고,
 * {@link #DELAY_SEC} 로 지연을 노출한다.
 */
public final class AccelEstimator {

    public static final double SAMPLE_HZ = 45.6;
    public static final int WINDOW = 45;
    /** 중심창 지연 = half / fs = 22 / 45.6. */
    public static final double DELAY_SEC = 22.0 / SAMPLE_HZ;

    private final Samples.Ring ring;
    private double accel;
    private long centerMillis;
    private boolean valid;

    public AccelEstimator() {
        this(WINDOW);
    }

    public AccelEstimator(int window) {
        this.ring = new Samples.Ring(window);
    }

    /**
     * @param speedMps 1 km/h 양자화된 속도(m/s).
     *
     * <p>프레임 드랍으로 샘플 간격이 {@link Samples#MAX_DT_SEC} 를 넘으면 불연속으로 보고
     * 창을 비운다 — 긴 공백을 가로질러 기울기를 재면 점수가 엉뚱해진다.
     */
    public void add(long elapsedMillis, double speedMps) {
        if (ring.size() > 0) {
            double gapSec = (elapsedMillis - ring.latestTime()) / 1000.0;
            if (Samples.clampDt(gapSec) <= 0.0 || gapSec > Samples.MAX_DT_SEC) {
                reset();
            }
        }
        ring.add(elapsedMillis, speedMps);
        if (ring.isFull()) {
            accel = SgDerivative.slopeFromRing(ring, 0, ring.size() - 1);
            centerMillis = ring.timeAt(ring.size() / 2);
            valid = true;
        }
    }

    public boolean isValid() {
        return valid;
    }

    public double accelMps2() {
        return accel;
    }

    public long centerMillis() {
        return centerMillis;
    }

    public double latestSpeedMps() {
        return ring.size() == 0 ? 0.0 : ring.latestValue();
    }

    public void reset() {
        ring.clear();
        accel = 0.0;
        centerMillis = 0L;
        valid = false;
    }
}
