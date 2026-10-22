package ai.pleos.coach.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SteerRateEstimatorTest {

    /** 1° 양자화 입력에서 부호·크기를 확인한다. */
    @Test
    public void recoversSignAndMagnitudeFromQuantizedSteering() {
        double fs = SteerRateEstimator.SAMPLE_HZ;   // 7.8
        double trueRate = 20.0;                     // °/s
        SteerRateEstimator est = new SteerRateEstimator();
        double last = 0.0;
        for (int i = 0; i < (int) (5 * fs); i++) {
            double deg = trueRate * i / fs;
            double quantized = Math.round(deg);     // 1° 계단
            est.add((long) Math.round(i * 1000.0 / fs), quantized);
            if (est.isValid()) {
                last = est.rateDegPerSec();
            }
        }
        assertTrue("양수 조향율이어야 한다: " + last, last > 0.0);
        assertEquals(trueRate, last, trueRate * 0.25);
    }

    /** 반대 방향은 부호가 뒤집힌다. */
    @Test
    public void reversesSignForOppositeDirection() {
        double fs = SteerRateEstimator.SAMPLE_HZ;
        SteerRateEstimator est = new SteerRateEstimator();
        double last = 0.0;
        for (int i = 0; i < (int) (5 * fs); i++) {
            double deg = -20.0 * i / fs;
            est.add((long) Math.round(i * 1000.0 / fs), Math.round(deg));
            if (est.isValid()) {
                last = est.rateDegPerSec();
            }
        }
        assertTrue("음수 조향율이어야 한다: " + last, last < 0.0);
        assertEquals(-20.0, last, 5.0);
    }
}
