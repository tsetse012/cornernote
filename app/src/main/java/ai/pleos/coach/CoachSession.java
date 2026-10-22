package ai.pleos.coach;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ai.pleos.coach.core.AccelEstimator;
import ai.pleos.coach.core.CauseLabeler;
import ai.pleos.coach.core.CornerSegmenter;
import ai.pleos.coach.core.Efficiency;
import ai.pleos.coach.core.EventDetector;
import ai.pleos.coach.core.Metrics;
import ai.pleos.coach.core.Samples;
import ai.pleos.coach.core.SteerRateEstimator;
import ai.pleos.coach.location.LocationSource;

/**
 * 주행 한 번의 신호 파이프라인.
 *
 * <p>속도·조향·브레이크·기어·위치·에너지를 받아 4지표와 코너별 원인을 만든다.
 * 데모 재생 모드와 실주행이 <b>같은 파이프라인</b>을 쓴다.
 */
public final class CoachSession {

    public static final String TAG = "CornerNote";
    private static final long EXIT_ACCEL_WINDOW_MS = 2500L;
    private static final long DECEL_LOOKBACK_MS = 8000L;

    // ── 신호처리 ────────────────────────────────────────────────────────
    private final AccelEstimator accel = new AccelEstimator();
    private final SteerRateEstimator steerRate = new SteerRateEstimator();
    private final EventDetector decelEvents = EventDetector.deceleration();
    private final CornerSegmenter segmenter = new CornerSegmenter();

    private final Samples.Ring speedRing = new Samples.Ring(30000);
    private final Samples.Ring steerRing = new Samples.Ring(2000);
    private final List<BrakeEdge> brakeEdges = new ArrayList<>();
    private final List<LocationSource.Sample> locations = new ArrayList<>();
    private final TimedSeries accelSeries = new TimedSeries();
    private final TimedSeries steerIndexSeries = new TimedSeries();

    private final Map<String, double[]> steerBandSum = new LinkedHashMap<>();

    private final boolean demoMode;
    private final long startElapsed;
    private long lastElapsed;
    private boolean finished;

    private double socStart = Double.NaN;
    private double socEnd = Double.NaN;
    private double capacityWh = Double.NaN;
    private double distanceKm = Double.NaN;
    private double distanceStartKm = Double.NaN;

    private double lastSpeedMps;
    private boolean haveSpeed;
    private double lastSteerDeg;
    private boolean haveSteer;
    private boolean brakePressed;
    private boolean haveBrake;
    private int gear = -1;

    private boolean locationAvailable;
    private boolean locationVirtual;

    public CoachSession(boolean demoMode) {
        this.demoMode = demoMode;
        this.startElapsed = android.os.SystemClock.elapsedRealtime();
        this.lastElapsed = startElapsed;
        for (String band : new String[]{"0-30", "30-60", "60-90", "90+"}) {
            steerBandSum.put(band, new double[]{0.0, 0.0});
        }
    }

    public boolean isDemoMode() {
        return demoMode;
    }

    public synchronized int confirmedCorners() {
        return segmenter.confirmedCount();
    }

    public synchronized long elapsedMillis() {
        return lastElapsed - startElapsed;
    }

    public synchronized double currentSpeedKph() {
        return haveSpeed ? lastSpeedMps * 3.6 : 0.0;
    }

    public synchronized double currentAccel() {
        return accel.isValid() ? accel.accelMps2() : 0.0;
    }

    public synchronized boolean hasLocation() {
        return locationAvailable;
    }

    // ── 입력 ────────────────────────────────────────────────────────────
    public synchronized void onSpeed(long elapsedMillis, float speedMps) {
        lastElapsed = Math.max(lastElapsed, elapsedMillis);
        lastSpeedMps = speedMps;
        haveSpeed = true;
        speedRing.add(elapsedMillis, speedMps);

        accel.add(elapsedMillis, speedMps);
        if (accel.isValid()) {
            accelSeries.add(accel.centerMillis(), accel.accelMps2());
            decelEvents.add(accel.centerMillis(), accel.accelMps2());
        }
        updateSteerIndex();
    }

    public synchronized void onSteer(long elapsedMillis, float steerDeg) {
        lastElapsed = Math.max(lastElapsed, elapsedMillis);
        lastSteerDeg = steerDeg;
        haveSteer = true;
        steerRing.add(elapsedMillis, steerDeg);
        steerRate.add(elapsedMillis, steerDeg);
        segmenter.add(elapsedMillis, steerDeg);
        updateSteerIndex();
    }

    private void updateSteerIndex() {
        // 조향 콜백은 값이 변할 때만 오므로, 속도 샘플마다 창을 굴려 조향율을 갱신한다
        steerRate.recompute(lastElapsed);
        if (!haveSpeed || !steerRate.isValid()) {
            return;
        }
        double index = Metrics.steerIndex(lastSpeedMps, steerRate.rateDegPerSec());
        steerIndexSeries.add(lastElapsed, index);
        double[] acc = steerBandSum.get(Metrics.speedBand(lastSpeedMps));
        if (acc != null) {
            acc[0] += index;
            acc[1] += 1.0;
        }
    }

    public synchronized void onBrake(long elapsedMillis, boolean pressed) {
        lastElapsed = Math.max(lastElapsed, elapsedMillis);
        if (!haveBrake) {
            haveBrake = true;
            brakePressed = pressed;
            brakeEdges.add(new BrakeEdge(elapsedMillis, pressed));
            return;
        }
        if (pressed != brakePressed) {
            brakePressed = pressed;
            brakeEdges.add(new BrakeEdge(elapsedMillis, pressed));
        }
    }

    public synchronized void onGear(int gearCode) {
        this.gear = gearCode;
    }

    public int gear() {
        return gear;
    }

    public synchronized void onLocation(long elapsedMillis, double lat, double lng, float heading) {
        lastElapsed = Math.max(lastElapsed, elapsedMillis);
        locationAvailable = true;
        locations.add(new LocationSource.Sample(elapsedMillis, lat, lng, heading));
    }

    public synchronized void onEnergy(long elapsedMillis, Integer socPercent, Float capacity, Float distance) {
        lastElapsed = Math.max(lastElapsed, elapsedMillis);
        if (socPercent != null) {
            if (Double.isNaN(socStart)) {
                socStart = socPercent;
            }
            socEnd = socPercent;
        }
        if (capacity != null) {
            capacityWh = Efficiency.normalizeCapacityWh(capacity);
        }
        if (distance != null) {
            if (Double.isNaN(distanceStartKm)) {
                distanceStartKm = distance;
            }
            distanceKm = distance;
        }
    }

    /** 위치를 못 쓰는 상황을 UI 가 알아야 한다. */
    public synchronized void markLocationUnavailable() {
        locationAvailable = false;
    }

    /** 가상 GPS 로 채운 좌표면 리포트에 '위치 없음'으로 표기한다. */
    public synchronized void markLocationVirtual() {
        locationVirtual = true;
    }

    // ── 종료·분석 ───────────────────────────────────────────────────────
    public synchronized Report finish() {
        if (finished && lastReport != null) {
            return lastReport;
        }
        finished = true;
        segmenter.flush();
        decelEvents.flush();
        lastReport = analyze();
        logMetrics(lastReport);
        return lastReport;
    }

    private Report lastReport;

    public Report lastReport() {
        return lastReport;
    }

    private Report analyze() {
        Report r = new Report();
        r.demoMode = demoMode;
        r.locationAvailable = locationAvailable && !locationVirtual;
        r.durationMillis = Math.max(0L, lastElapsed - startElapsed);
        r.cornerCount = segmenter.corners().size();

        // 지표 1 — 가속도 프로파일
        r.accelMeanAbs = accelSeries.meanAbs();
        r.accelP95Abs = accelSeries.p95Abs();
        r.decelEventCount = decelEvents.events().size();

        // 지표 2 — 조향 부드러움 (속도 구간별)
        r.steerIndexMean = steerIndexSeries.mean();
        for (Map.Entry<String, double[]> e : steerBandSum.entrySet()) {
            double[] acc = e.getValue();
            if (acc[1] > 0) {
                r.steerIndexByBand.put(e.getKey(), acc[0] / acc[1]);
            }
        }

        // 지표 4 — 전비
        // 세션 중 주행거리가 변했을 때만 "이번 주행 거리"로 쓴다.
        // 에뮬레이터는 getCurrentDistanceDriven 이 5.0 km 고정값을 주므로, 변화가 없으면
        // 그 값을 주행거리라고 주장하지 않고 미측정으로 둔다.
        if (!Double.isNaN(distanceStartKm) && !Double.isNaN(distanceKm)
                && distanceKm > distanceStartKm) {
            r.distanceKm = distanceKm - distanceStartKm;
            r.distanceMeasured = true;
        } else if (demoMode) {
            r.distanceKm = distanceKm; // 데모는 프로파일에서 직접 계산한 값을 넘긴다
            r.distanceMeasured = true;
        } else {
            r.distanceKm = Double.NaN;
            r.distanceMeasured = false;
        }
        r.capacityWh = Double.isNaN(capacityWh) ? Efficiency.DEFAULT_CAPACITY_WH : capacityWh;
        if (!Double.isNaN(socStart) && !Double.isNaN(socEnd) && socStart > socEnd) {
            r.deltaSoc = socStart - socEnd;
            r.energyKwh = Efficiency.energyKwh(r.deltaSoc, r.capacityWh);
            r.kmPerKwh = Efficiency.kmPerKwh(r.distanceKm, r.energyKwh);
            // SOC 가 1% 단위라 짧은 주행은 양자화 오차가 지배한다
            r.efficiencyReliable = r.distanceKm >= 2.0 && r.deltaSoc >= 1.0;
        } else {
            r.deltaSoc = Double.NaN;
            r.kmPerKwh = Double.NaN;
            r.energyKwh = Double.NaN;
        }

        // 코너별 사실
        List<CornerFacts> facts = new ArrayList<>();
        List<CornerSegmenter.Corner> corners = segmenter.corners();
        for (int i = 0; i < corners.size(); i++) {
            CornerSegmenter.Corner c = corners.get(i);
            CornerFacts f = new CornerFacts();
            f.index = i + 1;
            f.corner = c;
            f.entrySpeedMps = speedAt(c.startMillis);
            // 코너 시작 판정은 조향이 15°를 0.5초 지속한 시점이라 실제 조향 램프보다 늦다.
            // 조향율 피크는 그 앞(램프 구간)에 있으므로 1.5초 앞까지 본다.
            f.steerIndexPeak = steerIndexSeries.maxIn(c.startMillis - 1500L, c.endMillis);
            f.exitAccelPeak = accelSeries.maxIn(c.endMillis,
                    c.endMillis + EXIT_ACCEL_WINDOW_MS);
            f.transitionDelaySec = transitionDelayFor(c.startMillis);
            f.decelConcentration = decelConcentrationFor(c.startMillis);
            f.placeLabel = LocationSource.cornerLabel(locations,
                    (c.startMillis + c.endMillis) / 2);
            facts.add(f);
        }

        // 백분위는 이번 주행 코너 분포 기준
        double[] steerValues = new double[facts.size()];
        double[] exitValues = new double[facts.size()];
        double entrySum = 0;
        int entryCount = 0;
        for (int i = 0; i < facts.size(); i++) {
            steerValues[i] = facts.get(i).steerIndexPeak;
            exitValues[i] = facts.get(i).exitAccelPeak;
            if (!Double.isNaN(facts.get(i).entrySpeedMps)) {
                entrySum += facts.get(i).entrySpeedMps;
                entryCount++;
            }
        }
        double entryMean = entryCount > 0 ? entrySum / entryCount : Double.NaN;
        double[] steerSorted = steerValues.clone();
        double[] exitSorted = exitValues.clone();
        java.util.Arrays.sort(steerSorted);
        java.util.Arrays.sort(exitSorted);

        for (int i = 0; i < facts.size(); i++) {
            CornerFacts f = facts.get(i);
            f.steerPercentile = Metrics.percentileRank(steerSorted, steerSorted.length,
                    f.steerIndexPeak);
            f.exitPercentile = Metrics.percentileRank(exitSorted, exitSorted.length,
                    f.exitAccelPeak);
            f.entryAboveAverage = !Double.isNaN(entryMean)
                    && !Double.isNaN(f.entrySpeedMps)
                    && f.entrySpeedMps > entryMean;
            f.labels = CauseLabeler.labels(new CauseLabeler.Facts(
                    f.decelConcentration, f.transitionDelaySec,
                    f.steerPercentile, f.exitPercentile, f.entryAboveAverage));
            f.severity = CauseLabeler.severity(f.labels);
        }
        r.corners = facts;

        // 거친 코너 상위 3개만
        List<CornerFacts> ranked = new ArrayList<>(facts);
        Collections.sort(ranked, new Comparator<CornerFacts>() {
            @Override
            public int compare(CornerFacts a, CornerFacts b) {
                int sa = severityRank(a.severity);
                int sb = severityRank(b.severity);
                if (sa != sb) {
                    return Integer.compare(sa, sb);
                }
                if (a.labels.size() != b.labels.size()) {
                    return Integer.compare(b.labels.size(), a.labels.size());
                }
                return Double.compare(b.steerIndexPeak, a.steerIndexPeak);
            }
        });
        r.topCorners = new ArrayList<>(ranked.subList(0, Math.min(3, ranked.size())));

        // 이번 주 처방 1개
        for (CornerFacts f : ranked) {
            CauseLabeler.Label top = CauseLabeler.topPrescription(f.labels);
            if (top != null) {
                r.prescription = top;
                r.prescriptionCorner = f.index;
                break;
            }
        }

        // 지표 3 — 전환 지연 평균
        double sum = 0;
        int n = 0;
        for (CornerFacts f : facts) {
            if (!Double.isNaN(f.transitionDelaySec)) {
                sum += f.transitionDelaySec;
                n++;
            }
        }
        r.transitionDelaySec = n > 0 ? sum / n : Double.NaN;
        r.transitionSamples = n;

        return r;
    }

    private static int severityRank(CauseLabeler.Severity s) {
        switch (s) {
            case BAD:
                return 0;
            case WARN:
                return 1;
            default:
                return 2;
        }
    }

    // ── 신호 조회 ───────────────────────────────────────────────────────
    private double speedAt(long t) {
        if (speedRing.size() == 0) {
            return Double.NaN;
        }
        if (t <= speedRing.timeAt(0)) {
            return speedRing.valueAt(0);
        }
        if (t >= speedRing.latestTime()) {
            return speedRing.latestValue();
        }
        int lo = 0;
        int hi = speedRing.size() - 1;
        while (lo < hi - 1) {
            int mid = (lo + hi) >>> 1;
            if (speedRing.timeAt(mid) <= t) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        long dLo = Math.abs(speedRing.timeAt(lo) - t);
        long dHi = Math.abs(speedRing.timeAt(hi) - t);
        return dLo <= dHi ? speedRing.valueAt(lo) : speedRing.valueAt(hi);
    }

    /**
     * 코너 직전 감속 구간 [시작, 끝].
     *
     * <p>코너 시작은 조향이 15°를 0.5초 지속한 시점이라 <b>실제 브레이크보다 늦다</b>.
     * 그래서 코너 시작에서 뒤로 밀지 않고 코너 근처의 <b>속도 최소점</b>을 끝으로 잡는다.
     * 시작은 그 구간 안의 마지막 <b>브레이크 밟기 시작</b>. 브레이크 신호가 없으면
     * 속도가 평탄하던 구간(정속)의 끝으로 폴백한다.
     *
     * @return {onsetMillis, endMillis} 또는 null
     */
    private long[] decelWindow(long cornerStart) {
        if (speedRing.size() == 0) {
            return null;
        }
        long from = cornerStart - 1500L;
        long to = cornerStart + 3000L;
        long minTime = from;
        double minSpeed = Double.MAX_VALUE;
        for (long t = from; t <= to; t += 250L) {
            double v = speedAt(t);
            if (Double.isNaN(v)) {
                continue;
            }
            if (v < minSpeed - 1e-9) {
                minSpeed = v;
                minTime = t;
            }
        }
        if (minSpeed == Double.MAX_VALUE) {
            return null;
        }

        Long pressOn = lastBrakePress(minTime - DECEL_LOOKBACK_MS, minTime);
        long onset;
        if (pressOn != null) {
            onset = pressOn;
        } else {
            onset = plateauEnd(minTime);
        }
        return new long[]{onset, minTime};
    }

    /** 정속(평탄) 구간의 끝 — 1초 넘게 속도가 안 오르면 거기가 감속 시작. */
    private long plateauEnd(long endMillis) {
        long onset = endMillis;
        int flatSteps = 0;
        for (long t = endMillis - 250L; t > endMillis - DECEL_LOOKBACK_MS; t -= 250L) {
            double rise = speedAt(t) - speedAt(t + 250L);
            if (Double.isNaN(rise) || rise < 0.05) {
                if (++flatSteps > 4) {
                    break;
                }
                continue;
            }
            flatSteps = 0;
            onset = t;
        }
        return onset;
    }

    private double decelConcentrationFor(long cornerStart) {
        long[] window = decelWindow(cornerStart);
        if (window == null) {
            return Double.NaN;
        }
        long start = window[0];
        long end = window[1];
        if (end - start < 1500L) {
            return Double.NaN;
        }
        // 임계 이벤트(-2.5 m/s², 0.5초 지속)가 이 구간에 있어야 집중도를 계산한다
        boolean hasEvent = false;
        for (EventDetector.Event e : decelEvents.events()) {
            if (e.endMillis >= start - 1000L && e.startMillis <= end + 1000L) {
                hasEvent = true;
                break;
            }
        }
        if (!hasEvent) {
            return Double.NaN;
        }
        double vStart = speedAt(start);
        double vMid = speedAt(start + (long) ((end - start) * 2.0 / 3.0));
        double vEnd = speedAt(end);
        if (Double.isNaN(vStart) || Double.isNaN(vMid) || Double.isNaN(vEnd)) {
            return Double.NaN;
        }
        return Metrics.decelConcentration(vStart, vMid, vEnd);
    }

    /** 브레이크를 밟기 시작한( false→true ) 마지막 시각. */
    private Long lastBrakePress(long fromMillis, long toMillis) {
        for (int i = brakeEdges.size() - 1; i >= 0; i--) {
            BrakeEdge e = brakeEdges.get(i);
            if (!e.pressed || e.timeMillis < fromMillis || e.timeMillis > toMillis) {
                continue;
            }
            return e.timeMillis;
        }
        return null;
    }

    /** 브레이크가 떨어진( true→false ) 마지막 시각. */
    private Long lastBrakeRelease(long fromMillis, long toMillis) {
        for (int i = brakeEdges.size() - 1; i >= 0; i--) {
            BrakeEdge e = brakeEdges.get(i);
            if (e.pressed || e.timeMillis < fromMillis || e.timeMillis > toMillis) {
                continue;
            }
            return e.timeMillis;
        }
        return null;
    }

    /** 주어진 시각 이후 조향 절대값이 처음 5°를 넘는 시각. */
    private Long firstSteerOverAfter(long afterMillis, long limitMillis) {
        for (int i = 0; i < steerRing.size(); i++) {
            long t = steerRing.timeAt(i);
            if (t <= afterMillis) {
                continue;
            }
            if (t > limitMillis) {
                break;
            }
            if (Math.abs(steerRing.valueAt(i)) > 5.0) {
                return t;
            }
        }
        return null;
    }

    private double transitionDelayFor(long cornerStart) {
        Long brakeOff = lastBrakeRelease(cornerStart - 6000L, cornerStart + 1000L);
        if (brakeOff == null) {
            return Double.NaN;
        }
        Long steerOver = firstSteerOverAfter(brakeOff, brakeOff + 5000L);
        if (steerOver == null) {
            return Double.NaN;
        }
        return Metrics.transitionDelaySec(brakeOff, steerOver);
    }

    // ── 로그 ────────────────────────────────────────────────────────────
    private void logMetrics(Report r) {
        Log.i(TAG, String.format(Locale.US,
                "METRIC1 accel meanAbs=%.3f m/s2 p95=%.3f m/s2 decelEvents=%d",
                r.accelMeanAbs, r.accelP95Abs, r.decelEventCount));
        Log.i(TAG, String.format(Locale.US,
                "METRIC2 steerIndex mean=%.3f m/s3 bands=%s",
                r.steerIndexMean, r.steerIndexByBand));
        Log.i(TAG, String.format(Locale.US,
                "METRIC3 transitionDelay=%.3f s samples=%d",
                r.transitionDelaySec, r.transitionSamples));
        Log.i(TAG, String.format(Locale.US,
                "METRIC4 kmPerKwh=%.3f energy=%.3f kWh dSoc=%.0f%% dist=%.3f km reliable=%s",
                r.kmPerKwh, r.energyKwh, r.deltaSoc, r.distanceKm, r.efficiencyReliable));
        Log.i(TAG, String.format(Locale.US,
                "REPORT corners=%d durationMs=%d demo=%s location=%s",
                r.cornerCount, r.durationMillis, r.demoMode, r.locationAvailable));
        for (CornerFacts f : r.corners) {
            Log.i(TAG, String.format(Locale.US,
                    "CORNER #%d t=%d..%d peakSteer=%.0f entry=%.1f kph steerIdx=%.2f "
                            + "exitAccel=%.2f conc=%.2f delay=%.2f labels=%s severity=%s place=%s",
                    f.index, f.corner.startMillis, f.corner.endMillis, f.corner.peakSteerDeg,
                    f.entrySpeedMps * 3.6, f.steerIndexPeak, f.exitAccelPeak,
                    f.decelConcentration, f.transitionDelaySec, labelNames(f.labels),
                    f.severity, f.placeLabel));
        }
    }

    private static String labelNames(List<CauseLabeler.Label> labels) {
        if (labels.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (CauseLabeler.Label l : labels) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(l.title);
        }
        return sb.toString();
    }

    // ── 데이터 ──────────────────────────────────────────────────────────
    private static final class BrakeEdge {
        final long timeMillis;
        final boolean pressed;

        BrakeEdge(long timeMillis, boolean pressed) {
            this.timeMillis = timeMillis;
            this.pressed = pressed;
        }
    }

    /** 박싱 없이 (시각, 값) 을 쌓는 가변 배열. */
    private static final class TimedSeries {
        private long[] times = new long[2048];
        private double[] values = new double[2048];
        private int n;

        void add(long time, double value) {
            if (n == times.length) {
                long[] t2 = new long[n * 2];
                double[] v2 = new double[n * 2];
                System.arraycopy(times, 0, t2, 0, n);
                System.arraycopy(values, 0, v2, 0, n);
                times = t2;
                values = v2;
            }
            times[n] = time;
            values[n] = value;
            n++;
        }

        int size() {
            return n;
        }

        double mean() {
            if (n == 0) {
                return 0.0;
            }
            double s = 0;
            for (int i = 0; i < n; i++) {
                s += values[i];
            }
            return s / n;
        }

        double meanAbs() {
            if (n == 0) {
                return 0.0;
            }
            double s = 0;
            for (int i = 0; i < n; i++) {
                s += Math.abs(values[i]);
            }
            return s / n;
        }

        double p95Abs() {
            if (n == 0) {
                return 0.0;
            }
            double[] copy = new double[n];
            for (int i = 0; i < n; i++) {
                copy[i] = Math.abs(values[i]);
            }
            java.util.Arrays.sort(copy);
            int idx = (int) Math.round(0.95 * (n - 1));
            return copy[Math.max(0, Math.min(n - 1, idx))];
        }

        double maxIn(long from, long to) {
            double max = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                if (times[i] >= from && times[i] <= to) {
                    max = Math.max(max, values[i]);
                }
            }
            return max == Double.NEGATIVE_INFINITY ? 0.0 : max;
        }
    }

    /** 코너 하나의 계산된 사실. */
    public static final class CornerFacts {
        public int index;
        public CornerSegmenter.Corner corner;
        public String placeLabel;
        public double entrySpeedMps = Double.NaN;
        public double steerIndexPeak;
        public double exitAccelPeak;
        public double transitionDelaySec = Double.NaN;
        public double decelConcentration = Double.NaN;
        public double steerPercentile;
        public double exitPercentile;
        public boolean entryAboveAverage;
        public List<CauseLabeler.Label> labels = new ArrayList<>();
        public CauseLabeler.Severity severity = CauseLabeler.Severity.GOOD;

        public String displayPlace() {
            return placeLabel != null ? placeLabel : ("코너 " + index);
        }
    }

    /** 리포트 카드에 그대로 쓰는 결과. */
    public static final class Report {
        public boolean demoMode;
        public boolean locationAvailable;
        public long durationMillis;
        public int cornerCount;

        public double accelMeanAbs;
        public double accelP95Abs;
        public int decelEventCount;

        public double steerIndexMean;
        public final Map<String, Double> steerIndexByBand = new LinkedHashMap<>();

        public double transitionDelaySec = Double.NaN;
        public int transitionSamples;

        public double kmPerKwh = Double.NaN;
        /** SOC 1% 해상도로 신뢰할 만한지. 2km·1% 미만이면 숫자를 평가로 읽으면 안 된다. */
        public boolean efficiencyReliable;
        public double energyKwh = Double.NaN;
        public double deltaSoc = Double.NaN;
        public double distanceKm = Double.NaN;
        /** 주행거리를 실제로 쟀는지. false 면 전비도 계산하지 않는다. */
        public boolean distanceMeasured;
        public double capacityWh = Efficiency.DEFAULT_CAPACITY_WH;

        public List<CornerFacts> corners = new ArrayList<>();
        public List<CornerFacts> topCorners = new ArrayList<>();
        public CauseLabeler.Label prescription;
        public int prescriptionCorner;

        public String durationText() {
            long sec = durationMillis / 1000L;
            return String.format(Locale.US, "%d분 %02d초", sec / 60, sec % 60);
        }
    }
}
