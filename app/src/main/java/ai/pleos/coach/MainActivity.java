package ai.pleos.coach;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ai.pleos.coach.core.CauseLabeler;
import ai.pleos.coach.demo.DemoProfile;
import ai.pleos.coach.location.LocationSource;
import ai.pleos.coach.location.VirtualGps;
import ai.pleos.coach.ui.DriveScreen;
import ai.pleos.coach.ui.ReportScreen;
import ai.pleos.coach.vehicle.BrakeSource;
import ai.pleos.coach.vehicle.EnergySource;
import ai.pleos.coach.vehicle.GearSource;
import ai.pleos.coach.vehicle.SpeedSource;
import ai.pleos.coach.vehicle.SteerSource;
import ai.pleos.coach.vehicle.UxRestrictions;
import ai.pleos.coach.vehicle.VehicleSignals;
import ai.pleos.coach.voice.VoiceCoach;

/**
 * 「코너노트」 — 주행을 측정해 코너마다 원인을 짚고 처방을 준다.
 *
 * <p>주행 중 화면 0(최소) · 정차 시 리포트.
 * 모든 SDK 초기화는 신호별 개별 try/catch — 하나 실패해도 앱은 살아야 한다.
 */
public class MainActivity extends Activity {

    private static final String TAG = "CornerNote";
    private static final int STATE_IDLE = 0;
    private static final int STATE_DRIVING = 1;
    private static final int STATE_REPORT = 2;
    private static final int STATE_BLOCKED = 3;

    // SDK 래퍼
    private final VehicleSignals vehicleSignals = new VehicleSignals();
    private final SpeedSource speedSource = new SpeedSource();
    private final SteerSource steerSource = new SteerSource();
    private final BrakeSource brakeSource = new BrakeSource();
    private final GearSource gearSource = new GearSource();
    private final EnergySource energySource = new EnergySource();
    private final UxRestrictions uxRestrictions = new UxRestrictions();
    private final LocationSource locationSource = new LocationSource();
    private final VirtualGps virtualGps = new VirtualGps();
    private final VoiceCoach voice = new VoiceCoach();
    private final DemoProfile demoProfile = new DemoProfile();

    private final List<String> degraded = new ArrayList<>();
    private boolean realLocation;
    private boolean vehicleReady;

    private Handler uiHandler;
    private Handler demoHandler;
    private CoachSession session;
    private CoachSession.Report lastReport;
    private CoachSession.Report pendingReport;
    private DriveScreen driveScreen;
    private int state = STATE_IDLE;

    private long demoStartElapsed;
    private int lastSteerIdx = -1;
    private int lastBrakeIdx = -1;
    private int lastSecIdx = -1;
    private boolean sawDriveGear;
    private boolean hasMoved;
    /** 정차 판정: 마지막으로 움직인 시각. */
    private long lastMovingMillis;
    private static final double MOVING_KMH = 3.0;
    private static final long STOPPED_FINISH_MS = 5000L;
    private static final long MIN_LIVE_SESSION_MS = 15000L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        uiHandler = new Handler(Looper.getMainLooper());
        demoHandler = new Handler(Looper.getMainLooper());
        initSignals();
        showStart();
        startUiTicker();
    }

    // ── 초기화: 신호별 개별 try/catch (스펙 §9) ──────────────────────────
    private void initSignals() {
        try {
            vehicleReady = vehicleSignals.init(this);
            if (!vehicleReady) {
                degraded.add("vehicle");
            }
        } catch (Throwable t) {
            degraded.add("vehicle");
            Log.w(TAG, "vehicle init threw: " + t);
        }

        if (vehicleReady) {
            try {
                if (!steerSource.start(vehicleSignals.vehicle(), this::onSteerSample)) {
                    degraded.add("steer");
                }
            } catch (Throwable t) {
                degraded.add("steer");
                Log.w(TAG, "steer threw: " + t);
            }
            try {
                if (!brakeSource.start(vehicleSignals.vehicle(), this::onBrakeSample)) {
                    degraded.add("brake");
                }
            } catch (Throwable t) {
                degraded.add("brake");
                Log.w(TAG, "brake threw: " + t);
            }
            try {
                if (!gearSource.start(vehicleSignals.vehicle(), this::onGearSample)) {
                    degraded.add("gear");
                }
            } catch (Throwable t) {
                degraded.add("gear");
                Log.w(TAG, "gear threw: " + t);
            }
            try {
                if (!energySource.start(vehicleSignals.vehicle(), this::onEnergySample)) {
                    degraded.add("energy");
                }
            } catch (Throwable t) {
                degraded.add("energy");
                Log.w(TAG, "energy threw: " + t);
            }
        }

        try {
            if (!speedSource.start(this, this::onSpeedSample)) {
                degraded.add("speed");
            }
        } catch (Throwable t) {
            degraded.add("speed");
            Log.w(TAG, "speed threw: " + t);
        }

        try {
            if (!uxRestrictions.start(this, this::onRestrictionsChanged)) {
                degraded.add("uxrestriction");
            }
        } catch (Throwable t) {
            degraded.add("uxrestriction");
            Log.w(TAG, "ux threw: " + t);
        }

        try {
            if (!voice.init(this)) {
                degraded.add("tts");
            }
        } catch (Throwable t) {
            degraded.add("tts");
            Log.w(TAG, "tts threw: " + t);
        }

        try {
            // 위치는 요청하지 않는다. 없으면 가상 GPS 폴백.
            realLocation = locationSource.start(this, this::onLocationSample);
            if (!realLocation) {
                locationSource.markVirtual();
            }
        } catch (Throwable t) {
            realLocation = false;
            Log.w(TAG, "location threw: " + t);
        }

        Log.i(TAG, "init done. degraded=" + degraded + " realLocation=" + realLocation
                + " ux=" + uxRestrictions.failureReason() + " tts=" + voice.failureReason());
    }

    // ── 신호 콜백 ───────────────────────────────────────────────────────
    private void onSpeedSample(long t, float mps) {
        CoachSession s = session;
        if (s == null || s.isDemoMode()) {
            return;
        }
        s.onSpeed(t, mps);
        if (mps * 3.6 >= MOVING_KMH) {
            lastMovingMillis = t;
            hasMoved = true;
        } else if (hasMoved && state == STATE_DRIVING
                && t - lastMovingMillis >= STOPPED_FINISH_MS
                && s.elapsedMillis() >= MIN_LIVE_SESSION_MS) {
            Log.i(TAG, "standstill for " + STOPPED_FINISH_MS + "ms → finishing live session");
            // 속도 폴링 스레드에서 온 호출이다. 화면 전환은 메인 스레드로 넘긴다.
            uiHandler.post(this::finishSession);
        }
    }

    private void onSteerSample(long t, float deg) {
        CoachSession s = session;
        if (s != null && !s.isDemoMode()) {
            s.onSteer(t, deg);
        }
    }

    private void onBrakeSample(long t, boolean pressed) {
        CoachSession s = session;
        if (s != null && !s.isDemoMode()) {
            s.onBrake(t, pressed);
        }
    }

    private void onGearSample(int gear) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            uiHandler.post(() -> onGearSample(gear));
            return;
        }
        if (gear == GearSource.GEAR_DRIVE) {
            sawDriveGear = true;
        }
        CoachSession s = session;
        if (s != null) {
            s.onGear(gear);
        }
        if (state == STATE_IDLE && gear == GearSource.GEAR_DRIVE) {
            startLiveSession();
        } else if (state == STATE_DRIVING && s != null && !s.isDemoMode()
                && gear == GearSource.GEAR_PARK && sawDriveGear
                && s.elapsedMillis() > 10_000L) {
            finishSession();
        }
    }

    private void onEnergySample(long t, Integer soc, Float capacity, Float distance) {
        CoachSession s = session;
        if (s != null && !s.isDemoMode()) {
            s.onEnergy(t, soc, capacity, distance);
        }
    }

    private void onLocationSample(long t, double lat, double lng, float heading) {
        CoachSession s = session;
        if (s != null && !s.isDemoMode()) {
            s.onLocation(t, lat, lng, heading);
        }
    }

    private void onRestrictionsChanged(boolean requiresDo) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            uiHandler.post(() -> onRestrictionsChanged(requiresDo));
            return;
        }
        if (!requiresDo && pendingReport != null) {
            CoachSession.Report r = pendingReport;
            pendingReport = null;
            showReport(r);
        }
    }

    // ── 화면 ────────────────────────────────────────────────────────────
    private void showStart() {
        state = STATE_IDLE;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#121417"));
        int pad = dp(32);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView brand = new TextView(this);
        brand.setText("코너노트");
        brand.setTextColor(Color.parseColor("#F2F4F7"));
        brand.setTextSize(32f);
        brand.setGravity(Gravity.CENTER);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(brand, widthLp());

        TextView sub = new TextView(this);
        sub.setText("운전 습관 코치");
        sub.setTextColor(Color.parseColor("#5B9DFF"));
        sub.setTextSize(14f);
        sub.setLetterSpacing(0.1f);
        sub.setGravity(Gravity.CENTER);
        root.addView(sub, widthTopLp(dp(4)));

        TextView body = new TextView(this);
        body.setText("주행을 측정해 코너마다\n왜 거칠었는지 짚고 처방을 줍니다.");
        body.setTextColor(Color.parseColor("#9AA3AE"));
        body.setTextSize(15f);
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(dp(4), 1f);
        root.addView(body, widthTopLp(dp(20)));

        TextView status = new TextView(this);
        status.setText(String.format(Locale.US,
                "속도 %.1fHz · 조향 %.1fHz · 위치 %s · TTS %s",
                ai.pleos.coach.core.AccelEstimator.SAMPLE_HZ,
                ai.pleos.coach.core.SteerRateEstimator.SAMPLE_HZ,
                realLocation ? "연결" : "없음(가상 GPS)",
                voice.isReady() ? "준비" : "비활성"));
        status.setTextColor(Color.parseColor("#6B7480"));
        status.setTextSize(12f);
        status.setGravity(Gravity.CENTER);
        root.addView(status, widthTopLp(dp(14)));

        if (!degraded.isEmpty()) {
            TextView deg = new TextView(this);
            deg.setText("신호 일부 비활성: " + String.join(", ", degraded));
            deg.setTextColor(Color.parseColor("#E8C547"));
            deg.setTextSize(12f);
            deg.setGravity(Gravity.CENTER);
            root.addView(deg, widthTopLp(dp(6)));
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button live = new Button(this);
        live.setText("주행 시작");
        live.setAllCaps(false);
        live.setTextColor(Color.parseColor("#121417"));
        live.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Color.parseColor("#5B9DFF")));
        live.setOnClickListener(v -> startLiveSession());
        row.addView(live, weightLp());

        Button demo = new Button(this);
        demo.setText("데모 재생");
        demo.setAllCaps(false);
        demo.setTextColor(Color.parseColor("#F2F4F7"));
        demo.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Color.parseColor("#2A2F3A")));
        demo.setOnClickListener(v -> startDemoSession());
        LinearLayout.LayoutParams dlp = weightLp();
        dlp.leftMargin = dp(10);
        row.addView(demo, dlp);
        root.addView(row, widthTopLp(dp(28)));

        if (lastReport != null) {
            Button again = new Button(this);
            again.setText("지난 리포트 보기");
            again.setAllCaps(false);
            again.setTextColor(Color.parseColor("#9AA3AE"));
            again.setBackgroundColor(Color.TRANSPARENT);
            again.setOnClickListener(v -> showReport(lastReport));
            root.addView(again, widthTopLp(dp(8)));
        }

        setContentView(root);
    }

    private void showDriving() {
        state = STATE_DRIVING;
        driveScreen = new DriveScreen(this);
        setContentView(driveScreen.view());
        updateDrivingScreen();
    }

    private void updateDrivingScreen() {
        if (driveScreen == null || session == null) {
            return;
        }
        driveScreen.update(
                session.isDemoMode() ? "데모 주행 중" : "주행 중",
                session.confirmedCorners(),
                session.currentSpeedKph(),
                session.isDemoMode(),
                !realLocation || session.isDemoMode());
    }

    private void showReport(CoachSession.Report report) {
        if (uxRestrictions.requiresDistractionOptimization()) {
            pendingReport = report;
            showBlocked();
            return;
        }
        state = STATE_REPORT;
        ReportScreen screen = new ReportScreen(this, report, new ReportScreen.Actions() {
            @Override
            public void onRestartDemo() {
                startDemoSession();
            }

            @Override
            public void onClose() {
                showStart();
            }
        });
        setContentView(screen.view());
    }

    private void showBlocked() {
        state = STATE_BLOCKED;
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#121417"));
        TextView t = new TextView(this);
        t.setText("주행 중에는 리포트를 볼 수 없습니다.\n정차하면 자동으로 열립니다.");
        t.setTextColor(Color.parseColor("#9AA3AE"));
        t.setTextSize(16f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(32), dp(64), dp(32), dp(32));
        scroll.addView(t);
        setContentView(scroll);
    }

    // ── 세션 ────────────────────────────────────────────────────────────
    private void startLiveSession() {
        if (state == STATE_DRIVING) {
            return;
        }
        stopDemo();
        session = new CoachSession(false);
        if (!realLocation) {
            session.markLocationVirtual();
        }
        session.onGear(gearSource.lastGear());
        sawDriveGear = gearSource.lastGear() == GearSource.GEAR_DRIVE;
        lastMovingMillis = SystemClock.elapsedRealtime();
        hasMoved = false;
        showDriving();
        Log.i(TAG, "live session started. realLocation=" + realLocation);
    }

    private void startDemoSession() {
        stopDemo();
        session = new CoachSession(true);
        session.markLocationVirtual();
        demoStartElapsed = SystemClock.elapsedRealtime();
        lastSteerIdx = -1;
        lastBrakeIdx = -1;
        lastSecIdx = -1;
        showDriving();
        Log.i(TAG, "demo session started");
        demoHandler.post(demoTick);
    }

    private void stopDemo() {
        demoHandler.removeCallbacksAndMessages(null);
    }

    private final Runnable demoTick = new Runnable() {
        @Override
        public void run() {
            CoachSession s = session;
            if (s == null || !s.isDemoMode()) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            double tSec = (now - demoStartElapsed) / 1000.0;

            s.onSpeed(now, (float) demoProfile.speedMps(tSec));

            int steerIdx = (int) (tSec * 7.8);
            if (steerIdx != lastSteerIdx) {
                lastSteerIdx = steerIdx;
                s.onSteer(now, (float) demoProfile.steerDeg(tSec));
            }

            int brakeIdx = (int) (tSec * 13.0);
            if (brakeIdx != lastBrakeIdx) {
                lastBrakeIdx = brakeIdx;
                s.onBrake(now, demoProfile.brakePressed(tSec));
            }

            int secIdx = (int) tSec;
            if (secIdx != lastSecIdx) {
                lastSecIdx = secIdx;
                s.onEnergy(now, demoProfile.socPercent(tSec), 77400f,
                        (float) demoProfile.distanceKm(tSec));
            }

            LocationSource.Sample loc = virtualGps.sampleAt(s.elapsedMillis());
            s.onLocation(now, loc.lat, loc.lng, loc.heading);

            if (demoProfile.isFinished(tSec)) {
                finishSession();
                return;
            }
            demoHandler.postDelayed(this, 22L);
        }
    };

    private void finishSession() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            uiHandler.post(this::finishSession);
            return;
        }
        CoachSession s = session;
        if (s == null) {
            return;
        }
        stopDemo();
        session = null;
        CoachSession.Report report = s.finish();
        lastReport = report;
        speakSummary(report);
        showReport(report);
        Log.i(TAG, "session finished");
    }

    private void speakSummary(CoachSession.Report report) {
        int rough = 0;
        for (CoachSession.CornerFacts f : report.corners) {
            if (f.severity != CauseLabeler.Severity.GOOD) {
                rough++;
            }
        }
        String sentence;
        if (rough == 0) {
            sentence = "이번 주행은 코너가 모두 부드러웠어요. 이대로 유지해 보세요.";
        } else {
            sentence = String.format(Locale.US,
                    "이번 주행에 코너 %s 곳이 거칠었어요. 다음 코너는 조금 일찍 감속해 보세요.",
                    koreanNumber(rough));
        }
        boolean spoke = voice.speak(this, sentence);
        Log.i(TAG, "voice spoke=" + spoke + " sentence=" + sentence);
    }

    private static String koreanNumber(int n) {
        switch (n) {
            case 1:
                return "한";
            case 2:
                return "두";
            case 3:
                return "세";
            case 4:
                return "네";
            case 5:
                return "다섯";
            default:
                return String.valueOf(n);
        }
    }

    // ── UI 티커 ─────────────────────────────────────────────────────────
    private final Runnable uiTick = new Runnable() {
        @Override
        public void run() {
            if (state == STATE_DRIVING) {
                updateDrivingScreen();
            }
            uiHandler.postDelayed(this, 500L);
        }
    };

    private void startUiTicker() {
        uiHandler.post(uiTick);
    }

    // ── 생명주기 ────────────────────────────────────────────────────────
    @Override
    protected void onDestroy() {
        stopDemo();
        uiHandler.removeCallbacksAndMessages(null);
        try {
            speedSource.stop();
        } catch (Throwable ignored) {
        }
        try {
            steerSource.stop();
        } catch (Throwable ignored) {
        }
        try {
            brakeSource.stop();
        } catch (Throwable ignored) {
        }
        try {
            gearSource.stop();
        } catch (Throwable ignored) {
        }
        try {
            energySource.stop();
        } catch (Throwable ignored) {
        }
        try {
            uxRestrictions.stop();
        } catch (Throwable ignored) {
        }
        try {
            locationSource.stop();
        } catch (Throwable ignored) {
        }
        try {
            voice.release();
        } catch (Throwable ignored) {
        }
        try {
            vehicleSignals.release();
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    // ── 유틸 ────────────────────────────────────────────────────────────
    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams marginTop(int m) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = m;
        return p;
    }

    private static LinearLayout.LayoutParams widthLp() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams widthTopLp(int m) {
        LinearLayout.LayoutParams p = widthLp();
        p.topMargin = m;
        return p;
    }

    private static LinearLayout.LayoutParams weightLp() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    /** 테스트·자동화에서 현재 상태를 읽기 위한 훅. */
    public String debugState() {
        CoachSession s = session;
        return "state=" + state
                + " corners=" + (s == null ? -1 : s.confirmedCorners())
                + " demo=" + (s != null && s.isDemoMode());
    }
}
