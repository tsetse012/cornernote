package ai.pleos.coach.location;

import java.util.ArrayList;
import java.util.List;

/**
 * 가상 GPS 재생 모드 (스펙 §4-3 폴백).
 *
 * <p>위치 권한이 없거나 초기화에 실패하면 <b>코너 순서대로 미리 정의된 좌표</b>를
 * 1Hz 로 흘려보낸다. 실제 위치가 아니므로 UI 에 "위치 없이 측정 중" 배지를 띄운다.
 */
public final class VirtualGps {

    private static final double START_LAT = 37.497900;
    private static final double START_LNG = 127.027600;
    /** 14 m/s ≈ 50 km/h 기준 1초 이동량. */
    private static final double DEG_PER_SEC = 14.0 / 111_320.0;

    /** 코너 시각(초)과 그 구간의 진행 방위(도). */
    private static final double[][] HEADING_KEYS = {
            {0.0, 0.0},
            {11.0, 0.0},
            {15.0, 90.0},    // 코너 1 (13.6초 부근) — 오른쪽으로 돎
            {22.0, 90.0},
            {26.0, 0.0},     // 코너 2 (24.7초 부근) — 왼쪽으로 돎
            {41.0, 0.0},
            {45.0, 90.0},    // 코너 3 (43.2초 부근)
            {60.0, 90.0}
    };

    private final List<LocationSource.Sample> route = new ArrayList<>();

    public VirtualGps() {
        buildRoute(60);
    }

    private void buildRoute(int seconds) {
        double lat = START_LAT;
        double lng = START_LNG;
        for (int s = 0; s <= seconds; s++) {
            double heading = headingAt(s);
            route.add(new LocationSource.Sample(s * 1000L, lat, lng, (float) heading));
            double rad = Math.toRadians(heading);
            lat += Math.cos(rad) * DEG_PER_SEC;
            lng += Math.sin(rad) * DEG_PER_SEC;
        }
    }

    private static double headingAt(double tSec) {
        for (int i = 1; i < HEADING_KEYS.length; i++) {
            double t1 = HEADING_KEYS[i][0];
            if (tSec <= t1) {
                double t0 = HEADING_KEYS[i - 1][0];
                double h0 = HEADING_KEYS[i - 1][1];
                double h1 = HEADING_KEYS[i][1];
                double span = t1 - t0;
                if (span <= 0) {
                    return h1;
                }
                double f = (tSec - t0) / span;
                return h0 + (h1 - h0) * Math.max(0.0, Math.min(1.0, f));
            }
        }
        return HEADING_KEYS[HEADING_KEYS.length - 1][1];
    }

    /** 경과 시간(ms)에 해당하는 가상 좌표. 60초를 넘으면 마지막 점. */
    public LocationSource.Sample sampleAt(long elapsedMillis) {
        int idx = (int) (elapsedMillis / 1000L);
        if (idx < 0) {
            idx = 0;
        }
        if (idx >= route.size()) {
            idx = route.size() - 1;
        }
        return route.get(idx);
    }

    public List<LocationSource.Sample> route() {
        return route;
    }
}
