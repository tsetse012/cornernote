package ai.pleos.coach.demo;

/**
 * 데모 재생 모드의 주행 프로파일 (스펙 §8).
 *
 * <p>콘솔 주입 없이 앱 안에서 재생한다. 정속 → 늦은 감속 코너 → 정상 코너 → 급조향 코너, 약 60초.
 * 신호는 플랫폼과 같은 해상도로 양자화한다(속도 1 km/h, 조향 1°).
 */
public final class DemoProfile {

    public static final long DURATION_MS = 60_000L;

    /** {초, km/h} */
    private static final double[][] SPEED_KEYS = {
            {0.0, 60.0},
            {9.0, 60.0},
            {12.6, 53.0},    // 코너 1: 늦은 감속 — 오래 달리다 마지막에 몰아서 줄인다
            {13.4, 36.0},
            {17.0, 36.0},
            {20.0, 46.0},
            {22.0, 46.0},
            {24.7, 30.0},    // 코너 2: 정상 — 미리 고르게 감속
            {29.0, 30.0},
            {32.0, 33.0},
            {40.0, 33.0},
            {43.0, 26.0},    // 코너 3: 급조향
            {47.0, 26.0},
            {50.0, 33.0},
            {60.0, 33.0}
    };

    /** {초, 도} */
    private static final double[][] STEER_KEYS = {
            {0.0, 0.0},
            {12.4, 0.0},
            {14.4, 25.0},
            {17.0, 25.0},
            {17.6, 0.0},
            {23.6, 0.0},
            {25.1, -20.0},
            {28.4, -20.0},
            {29.0, 0.0},
            {43.0, 0.0},
            {43.45, 45.0},
            {46.6, 45.0},
            {47.0, 0.0},
            {60.0, 0.0}
    };

    /** {초, 0/1} */
    private static final double[][] BRAKE_KEYS = {
            {0.0, 0.0},
            {9.4, 1.0},
            {11.8, 0.0},    // 코너 1: 브레이크를 일찍 떼고 한참 뒤에 꺾는다 → 전환 지연 큼
            {21.8, 1.0},
            {23.4, 0.0},    // 코너 2: 브레이크를 떼자마자 자연스럽게 전환
            {40.5, 1.0},
            {42.9, 0.0},    // 코너 3
            {60.0, 0.0}
    };

    /** {초, %} */
    private static final double[][] SOC_KEYS = {
            {0.0, 82.0},
            {60.0, 81.0}
    };

    private final double[] distanceAtKey = new double[SPEED_KEYS.length];

    public DemoProfile() {
        double km = 0.0;
        distanceAtKey[0] = 0.0;
        for (int i = 1; i < SPEED_KEYS.length; i++) {
            double dt = SPEED_KEYS[i][0] - SPEED_KEYS[i - 1][0];
            double avgKph = (SPEED_KEYS[i][1] + SPEED_KEYS[i - 1][1]) / 2.0;
            km += avgKph * dt / 3600.0;
            distanceAtKey[i] = km;
        }
    }

    private static double lerp(double[][] keys, double tSec) {
        if (tSec <= keys[0][0]) {
            return keys[0][1];
        }
        for (int i = 1; i < keys.length; i++) {
            if (tSec <= keys[i][0]) {
                double t0 = keys[i - 1][0];
                double t1 = keys[i][0];
                double v0 = keys[i - 1][1];
                double v1 = keys[i][1];
                double span = t1 - t0;
                if (span <= 0) {
                    return v1;
                }
                double f = (tSec - t0) / span;
                return v0 + (v1 - v0) * f;
            }
        }
        return keys[keys.length - 1][1];
    }

    /** 플랫폼과 같은 1 km/h 양자화. */
    public double speedKph(double tSec) {
        return Math.round(lerp(SPEED_KEYS, tSec));
    }

    public double speedMps(double tSec) {
        return speedKph(tSec) / 3.6;
    }

    /** 플랫폼과 같은 1° 양자화. */
    public double steerDeg(double tSec) {
        return Math.round(lerp(STEER_KEYS, tSec));
    }

    /** 브레이크는 boolean 이므로 보간하지 않는다 — 마지막 키 값을 그대로 쓴다. */
    public boolean brakePressed(double tSec) {
        double value = BRAKE_KEYS[0][1];
        for (double[] key : BRAKE_KEYS) {
            if (tSec >= key[0]) {
                value = key[1];
            } else {
                break;
            }
        }
        return value > 0.5;
    }

    public int socPercent(double tSec) {
        return (int) Math.round(lerp(SOC_KEYS, tSec));
    }

    public double distanceKm(double tSec) {
        if (tSec <= 0) {
            return 0.0;
        }
        for (int i = 1; i < SPEED_KEYS.length; i++) {
            if (tSec <= SPEED_KEYS[i][0]) {
                double t0 = SPEED_KEYS[i - 1][0];
                double dt = tSec - t0;
                double avgKph = (SPEED_KEYS[i - 1][1] + lerp(SPEED_KEYS, tSec)) / 2.0;
                return distanceAtKey[i - 1] + avgKph * dt / 3600.0;
            }
        }
        return distanceAtKey[distanceAtKey.length - 1];
    }

    public boolean isFinished(double tSec) {
        return tSec * 1000.0 >= DURATION_MS;
    }
}
