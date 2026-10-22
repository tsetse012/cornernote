package ai.pleos.coach.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AccelEstimatorTest {

    private static final double FS = 45.6;
    private static final double DT_MS = 1000.0 / FS;

    /** 1 km/h 양자화 계단 입력에서 램프 기울기 오차 < 15%. */
    @Test
    public void rampSlopeErrorUnder15PercentOnQuantizedInput() {
        double trueAccel = 1.47;          // m/s²
        double v0 = 40.0 / 3.6;           // 40 kph
        AccelEstimator est = new AccelEstimator();
        double sum = 0.0;
        int n = 0;
        for (int i = 0; i < (int) (10 * FS); i++) {
            double tSec = i / FS;
            double v = v0 + trueAccel * tSec;
            double quantized = Math.round(v * 3.6) / 3.6; // 1 km/h 계단
            est.add((long) Math.round(i * DT_MS), quantized);
            if (est.isValid() && tSec > 4.0 && tSec < 9.0) {
                sum += est.accelMps2();
                n++;
            }
        }
        assertTrue("추정 샘플이 충분해야 한다", n > 100);
        double mean = sum / n;
        double errorRatio = Math.abs(mean - trueAccel) / trueAccel;
        assertTrue("오차율 " + errorRatio + " < 0.15", errorRatio < 0.15);
    }

    /** 정속 입력에서 잡음 < 0.1 m/s². */
    @Test
    public void constantSpeedNoiseUnderPointOne() {
        double v = 50.0 / 3.6;
        AccelEstimator est = new AccelEstimator();
        double maxAbs = 0.0;
        for (int i = 0; i < (int) (8 * FS); i++) {
            double quantized = Math.round(v * 3.6) / 3.6;
            est.add((long) Math.round(i * DT_MS), quantized);
            if (est.isValid()) {
                maxAbs = Math.max(maxAbs, Math.abs(est.accelMps2()));
            }
        }
        assertTrue("정속 잡음 " + maxAbs + " < 0.1", maxAbs < 0.1);
    }

    /** 프레임 드랍(1초 초과 공백)은 창을 비우고 다시 시작한다 — dt 상한 1.0 적용. */
    @Test
    public void frameDropResetsWindowInsteadOfBridging() {
        AccelEstimator est = new AccelEstimator();
        for (int i = 0; i < 60; i++) {
            est.add(i * 22L, 10.0);
        }
        assertTrue(est.isValid());
        // 3초 공백
        est.add(60 * 22L + 3000L, 10.0);
        assertTrue("공백 직후에는 창이 비어 있어야 한다", !est.isValid());
    }

    /** 중심창 지연은 0.48초다. */
    @Test
    public void centerWindowDelayIsAboutHalfSecond() {
        assertEquals(0.4825, AccelEstimator.DELAY_SEC, 1e-4);
    }
}
