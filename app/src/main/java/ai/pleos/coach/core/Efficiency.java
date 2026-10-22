package ai.pleos.coach.core;

/**
 * 지표 4 — 전비.
 *
 * <pre>
 * 사용에너지(kWh) = ΔSOC% / 100 × 배터리용량(Wh) / 1000
 * 전비(km/kWh)    = 주행거리(km) ÷ 사용에너지(kWh)
 * </pre>
 *
 * <p>{@code getInstantaneousEvEfficiency} 계열은 쓰지 않는다(에뮬레이터 미지원).
 */
public final class Efficiency {

    /** 차량이 용량을 안 줄 때 쓰는 기본값 (Wh). */
    public static final double DEFAULT_CAPACITY_WH = 77400.0;
    /** 이 값보다 작으면 kWh 단위로 보고 Wh 로 환산한다. */
    public static final double KWH_DETECT_THRESHOLD = 1000.0;

    private Efficiency() {
    }

    /** 차량이 주는 용량값의 단위를 Wh 로 정규화한다. */
    public static double normalizeCapacityWh(double raw) {
        if (Double.isNaN(raw) || raw <= 0.0) {
            return DEFAULT_CAPACITY_WH;
        }
        return raw < KWH_DETECT_THRESHOLD ? raw * 1000.0 : raw;
    }

    public static double energyKwh(double deltaSocPercent, double capacityWh) {
        return deltaSocPercent / 100.0 * capacityWh / 1000.0;
    }

    /**
     * @return km/kWh. 계산 불가(에너지 0, 거리 0)면 {@link Double#NaN}.
     */
    public static double kmPerKwh(double distanceKm, double energyKwh) {
        if (energyKwh <= 1e-9 || distanceKm <= 0.0) {
            return Double.NaN;
        }
        return distanceKm / energyKwh;
    }

    /** SOC 두 값과 거리로 바로 전비를 낸다. */
    public static double kmPerKwhFromSoc(double socStartPercent, double socEndPercent,
                                         double capacityWh, double distanceKm) {
        double delta = socStartPercent - socEndPercent;
        if (delta <= 0.0) {
            return Double.NaN;
        }
        return kmPerKwh(distanceKm, energyKwh(delta, capacityWh));
    }
}
