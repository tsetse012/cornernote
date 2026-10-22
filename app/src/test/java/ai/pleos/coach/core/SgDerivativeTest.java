package ai.pleos.coach.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SgDerivativeTest {

    /** 선형 램프에서 기울기를 정확히 복원한다 (polyorder=1 SG = 최소자승 기울기). */
    @Test
    public void restoresSlopeOfLinearRamp() {
        double dt = 1.0 / 45.6;
        double trueSlope = -1.3; // m/s²
        double[] v = new double[45];
        for (int i = 0; i < v.length; i++) {
            v[i] = 11.1 + trueSlope * i * dt;
        }
        double est = SgDerivative.slopeUniform(v, 0, v.length - 1, dt);
        assertEquals(trueSlope, est, 1e-9);

        // 중심 인덱스 기준으로 잘라도 같은 값이어야 한다
        double est2 = SgDerivative.slopeUniform(v, 10, 34, dt);
        assertEquals(trueSlope, est2, 1e-9);
    }

    /** 비균일 dt 에서도 같은 기울기를 복원한다. */
    @Test
    public void restoresSlopeWithNonUniformSpacing() {
        double[] t = {0.0, 0.021, 0.043, 0.064, 0.086, 0.108, 0.129};
        double[] v = new double[t.length];
        for (int i = 0; i < t.length; i++) {
            v[i] = 5.0 + 2.5 * t[i];
        }
        double est = SgDerivative.slopeNonUniform(t, v, 0, t.length - 1);
        assertEquals(2.5, est, 1e-9);
    }
}
