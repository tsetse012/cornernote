package ai.pleos.coach.core;

/**
 * 지표 4개 계산. 전부 순수 함수.
 */
public final class Metrics {

    /** 축거 기본값(m). 조향 부드러움 지수에 상수로 흡수된다. */
    public static final double WHEELBASE_M = 2.7;
    /** 저속에서 지수가 0에 붙는 것을 막는 하한. */
    public static final double MIN_SPEED_MPS = 5.0;

    /** 감속 이벤트 임계 (m/s²). 양자화 1스텝 0.278의 9~11배. */
    public static final double DECEL_TRIGGER = -2.5;
    /** 해제 임계 = 트리거보다 0.5 완화. */
    public static final double DECEL_RELEASE = -2.0;

    private Metrics() {
    }

    // ── 지표 2: 조향 부드러움 ─────────────────────────────────────────────
    /**
     * steerIndex = v²·|δ̇|/L. v 는 최소 5 m/s 로 클램프.
     *
     * @param speedMps           차속
     * @param steerRateDegPerSec 조향율(도/초)
     * @return m/s³
     */
    public static double steerIndex(double speedMps, double steerRateDegPerSec) {
        double v = Math.max(speedMps, MIN_SPEED_MPS);
        double rateRad = Math.toRadians(Math.abs(steerRateDegPerSec));
        return v * v * rateRad / WHEELBASE_M;
    }

    /** 속도 구간 라벨. 지표 2를 구간별로 나눠 볼 때 쓴다. */
    public static String speedBand(double speedMps) {
        double kph = speedMps * 3.6;
        if (kph < 30.0) {
            return "0-30";
        }
        if (kph < 60.0) {
            return "30-60";
        }
        if (kph < 90.0) {
            return "60-90";
        }
        return "90+";
    }

    // ── 지표 1: 감속 집중도 ──────────────────────────────────────────────
    /**
     * 한 감속 이벤트에서 "마지막 1/3 구간의 감속량 / 전체 감속량".
     *
     * @param vStartMps     이벤트 시작 속도
     * @param vTwoThirdMps  이벤트 2/3 시점 속도
     * @param vEndMps       이벤트 종료 속도
     * @return 0..1. 감속이 없으면 0.
     */
    public static double decelConcentration(double vStartMps, double vTwoThirdMps, double vEndMps) {
        double total = vStartMps - vEndMps;
        if (total <= 1e-6) {
            return 0.0;
        }
        double lastThird = vTwoThirdMps - vEndMps;
        double ratio = lastThird / total;
        if (ratio < 0.0) {
            return 0.0;
        }
        return Math.min(1.0, ratio);
    }

    /** 가속도 시계열의 크기 분포 — 평균 절대값. */
    public static double meanAbs(double[] values, int count) {
        if (count <= 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 0; i < count; i++) {
            sum += Math.abs(values[i]);
        }
        return sum / count;
    }

    /** 가속도 시계열의 크기 분포 — 95퍼센타일 절대값. */
    public static double p95Abs(double[] values, int count) {
        if (count <= 0) {
            return 0.0;
        }
        double[] copy = new double[count];
        for (int i = 0; i < count; i++) {
            copy[i] = Math.abs(values[i]);
        }
        java.util.Arrays.sort(copy);
        int idx = (int) Math.round(0.95 * (count - 1));
        return copy[Math.max(0, Math.min(count - 1, idx))];
    }

    // ── 지표 3: 브레이크-조향 전환 지연 ──────────────────────────────────
    public static final double TRANSITION_MIN_MEASURABLE_SEC = 0.1;
    public static final double TRANSITION_UNCERTAINTY_SEC = 0.1;

    /**
     * 브레이크가 false 로 떨어진 시각 → 조향 절대값이 5°를 넘는 시각의 차이.
     *
     * @return 초. 0.1초 미만이거나 음수면 {@link Double#NaN} ("측정 불가").
     */
    public static double transitionDelaySec(long brakeOffMillis, long steerOverMillis) {
        if (brakeOffMillis <= 0L || steerOverMillis <= 0L) {
            return Double.NaN;
        }
        double sec = (steerOverMillis - brakeOffMillis) / 1000.0;
        if (sec < TRANSITION_MIN_MEASURABLE_SEC) {
            return Double.NaN;
        }
        return sec;
    }

    // ── 지표 4: 전비 (구현은 Efficiency) ────────────────────────────────
    public static double energyKwh(double deltaSocPercent, double capacityWh) {
        return Efficiency.energyKwh(deltaSocPercent, capacityWh);
    }

    public static double kmPerKwh(double distanceKm, double energyKwh) {
        return Efficiency.kmPerKwh(distanceKm, energyKwh);
    }

    /** 백분위 순위(0..1). 값이 클수록 상위. */
    public static double percentileRank(double[] sortedAscending, int count, double value) {
        if (count <= 0) {
            return 0.0;
        }
        int below = 0;
        for (int i = 0; i < count; i++) {
            if (sortedAscending[i] < value) {
                below++;
            }
        }
        return count == 1 ? 1.0 : below / (double) (count - 1);
    }
}
