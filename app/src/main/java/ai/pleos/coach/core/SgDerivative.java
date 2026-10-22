package ai.pleos.coach.core;

/**
 * Savitzky-Golay 1차 미분 (polyorder=1, deriv=1).
 *
 * <p>polyorder=1 의 SG 미분은 최소자승 1차 회귀 기울기와 수학적으로 동일하다.
 * 균일 간격일 때 닫힌형은
 * <pre>a = Σ_{j=-half}^{half} j·v(t+jΔt) / (Δt · Σj²)</pre>
 * 이고, 이 클래스는 같은 값을 일반 최소자승으로 계산한다(비균일 dt 지원).
 */
public final class SgDerivative {

    private SgDerivative() {
    }

    /** 균일 간격 구간 [from, to] 의 기울기. dtSec 는 샘플 간격. */
    public static double slopeUniform(double[] values, int from, int to, double dtSec) {
        if (dtSec <= 0.0) {
            return 0.0;
        }
        int n = to - from + 1;
        if (n < 2) {
            return 0.0;
        }
        double mean = (n - 1) / 2.0;
        double num = 0.0;
        double den = 0.0;
        for (int i = 0; i < n; i++) {
            double x = i - mean;
            num += x * values[from + i];
            den += x * x;
        }
        if (den == 0.0) {
            return 0.0;
        }
        return num / (den * dtSec);
    }

    /** 비균일 간격 구간 [from, to] 의 기울기 (단위: 값/초). tSec 는 초 단위. */
    public static double slopeNonUniform(double[] tSec, double[] values, int from, int to) {
        int n = to - from + 1;
        if (n < 2) {
            return 0.0;
        }
        double sumT = 0.0;
        double sumV = 0.0;
        for (int i = from; i <= to; i++) {
            sumT += tSec[i];
            sumV += values[i];
        }
        double meanT = sumT / n;
        double meanV = sumV / n;
        double num = 0.0;
        double den = 0.0;
        for (int i = from; i <= to; i++) {
            double x = tSec[i] - meanT;
            num += x * (values[i] - meanV);
            den += x * x;
        }
        if (den <= 1e-12) {
            return 0.0;
        }
        return num / den;
    }

    /** 링버퍼의 밀리초 타임스탬프를 초로 바꿔 비균일 기울기를 계산한다. */
    public static double slopeFromRing(Samples.Ring ring, int from, int to) {
        int n = to - from + 1;
        if (n < 2) {
            return 0.0;
        }
        long t0 = ring.timeAt(from);
        double[] tSec = new double[n];
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            tSec[i] = (ring.timeAt(from + i) - t0) / 1000.0;
            values[i] = ring.valueAt(from + i);
        }
        return slopeNonUniform(tSec, values, 0, n - 1);
    }

    /** 초당 표본수와 창 길이(초)로 필요한 표본 수를 구한다. */
    public static int windowSamples(double sampleHz, double windowSec) {
        return Math.max(3, (int) Math.round(sampleHz * windowSec));
    }
}
