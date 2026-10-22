package ai.pleos.coach.location;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import ai.pleos.playground.location.fused.FusedLocation;
import ai.pleos.playground.location.fused.listener.FusedLocationListener;
import ai.pleos.playground.location.fused.model.LocationData;
import ai.pleos.coach.core.Samples;

/**
 * Pleos FusedLocation 래퍼 + 시간정렬 + 폴백.
 *
 * <p><b>권한은 요청하지 않는다.</b> {@code pleos.car.permission.FUSED_LOCATION} 은
 * dangerous 인데 에뮬레이터의 권한 승인 UI 가 크래시한다(실측). 이미 있으면 쓰고,
 * 없으면 가상 GPS 재생 모드로 떨어진다.
 */
public final class LocationSource {

    private static final String TAG = "CornerNote";
    public static final String PERMISSION = "pleos.car.permission.FUSED_LOCATION";

    public interface Listener {
        void onLocation(long elapsedMillis, double lat, double lng, float heading);
    }

    /** 위치 샘플 하나. elapsed 축으로 정렬해 둔다. */
    public static final class Sample {
        public final long elapsedMillis;
        public final double lat;
        public final double lng;
        public final float heading;

        public Sample(long elapsedMillis, double lat, double lng, float heading) {
            this.elapsedMillis = elapsedMillis;
            this.lat = lat;
            this.lng = lng;
            this.heading = heading;
        }
    }

    private FusedLocation fused;
    private Listener listener;
    private long offsetMillis;
    private boolean hasOffset;
    private boolean virtual;
    private String failureReason;

    private final FusedLocationListener callback = new FusedLocationListener() {
        @Override
        public void onChanged(LocationData data) {
            if (data == null) {
                return;
            }
            if (!hasOffset) {
                offsetMillis = Samples.offsetFrom(System.currentTimeMillis(),
                        android.os.SystemClock.elapsedRealtime());
                hasOffset = true;
            }
            long elapsed = Samples.wallToElapsed(data.getTimestampMillis(), offsetMillis);
            long nowOffset = Samples.offsetFrom(System.currentTimeMillis(),
                    android.os.SystemClock.elapsedRealtime());
            if (Samples.offsetDrifted(offsetMillis, nowOffset)) {
                offsetMillis = nowOffset; // 벽시계 점프 → 재계산
                elapsed = Samples.wallToElapsed(data.getTimestampMillis(), offsetMillis);
            }
            if (listener != null) {
                listener.onLocation(elapsed, data.getLatitude(), data.getLongitude(),
                        data.getHeading());
            }
        }

        @Override
        public void onError(Exception e) {
            Log.w(TAG, "location error: " + e);
        }
    };

    public static boolean hasPermission(Context context) {
        try {
            return context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * @return 실위치 사용 가능 여부. false 면 호출자가 가상 GPS 를 켠다.
     */
    public boolean start(Context context, Listener listener) {
        this.listener = listener;
        if (!hasPermission(context)) {
            failureReason = "permission not granted";
            Log.i(TAG, "location permission missing → virtual GPS mode");
            return false;
        }
        try {
            fused = new FusedLocation(context);
            fused.initialize();
            fused.registerFusedLocationCallback(callback);
            offsetMillis = Samples.offsetFrom(System.currentTimeMillis(),
                    android.os.SystemClock.elapsedRealtime());
            hasOffset = true;
            return true;
        } catch (Throwable t) {
            failureReason = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "location init failed: " + failureReason);
            fused = null;
            return false;
        }
    }

    public void markVirtual() {
        virtual = true;
    }

    public boolean isVirtual() {
        return virtual;
    }

    public String failureReason() {
        return failureReason;
    }

    public void stop() {
        try {
            if (fused != null) {
                fused.unregisterFusedLocationCallback(callback);
                fused.release();
            }
        } catch (Throwable ignored) {
        }
        fused = null;
    }

    // ── 코너 라벨링 ──────────────────────────────────────────────────────
    /** 코너 시작·중앙·끝 시각에 가장 가까운 위치 샘플을 고른다. */
    public static Sample nearest(List<Sample> samples, long elapsedMillis) {
        if (samples == null || samples.isEmpty()) {
            return null;
        }
        Sample best = null;
        long bestDelta = Long.MAX_VALUE;
        for (Sample s : samples) {
            long d = Math.abs(s.elapsedMillis - elapsedMillis);
            if (d < bestDelta) {
                bestDelta = d;
                best = s;
            }
        }
        // 1Hz 샘플이므로 2초 넘게 떨어져 있으면 매칭으로 보지 않는다
        return bestDelta <= 2000L ? best : null;
    }

    /** 진행 방위를 8방위 한글 표기로. 역지오코딩은 하지 않는다(네트워크·API 없음). */
    public static String bearingLabel(float headingDeg) {
        String[] names = {"북쪽", "북동쪽", "동쪽", "남동쪽", "남쪽", "남서쪽", "서쪽", "북서쪽"};
        double h = ((headingDeg % 360.0) + 360.0) % 360.0;
        int idx = (int) Math.round(h / 45.0) % 8;
        return names[idx];
    }

    /** 예: "북동쪽으로 도는 코너". 위치가 없으면 null. */
    public static String cornerLabel(List<Sample> samples, long midMillis) {
        Sample s = nearest(samples, midMillis);
        if (s == null) {
            return null;
        }
        return bearingLabel(s.heading) + "으로 도는 코너";
    }

    /** 위치 샘플 목록을 시간순으로 유지하는 헬퍼. */
    public static final class Track {
        private final List<Sample> samples = new ArrayList<>();

        public void add(Sample s) {
            samples.add(s);
        }

        public List<Sample> all() {
            return samples;
        }

        public int size() {
            return samples.size();
        }

        public void clear() {
            samples.clear();
        }
    }

    /** 진단용: 시스템 LocationManager 존재 여부(폴백 판단에 쓰지 않는다). */
    public static boolean systemLocationPresent(Context context) {
        try {
            return context.getSystemService(LocationManager.class) != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
