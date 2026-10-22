package ai.pleos.coach.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

import ai.pleos.coach.CoachSession;
import ai.pleos.coach.core.CauseLabeler;

/**
 * 정차 리포트 카드 (스펙 §6-2).
 *
 * <p>이번 주행 요약 → 거친 코너 3개(색+라벨+처방) → 전비 → "이번 주 처방 1개".
 * 점수 0~100 은 표시하지 않는다. 사건 단위 언어("코너 3곳")를 쓴다.
 */
public final class ReportScreen {

    public interface Actions {
        void onRestartDemo();

        void onClose();
    }

    private static final int BG = Color.parseColor("#121417");
    private static final int SURFACE = Color.parseColor("#1C1F24");
    private static final int TEXT = Color.parseColor("#F2F4F7");
    private static final int MUTED = Color.parseColor("#9AA3AE");
    private static final int ACCENT = Color.parseColor("#5B9DFF");

    private final Context context;
    private final CoachSession.Report report;
    private final Actions actions;
    private final ScrollView root;

    public ReportScreen(Context context, CoachSession.Report report, Actions actions) {
        this.context = context;
        this.report = report;
        this.actions = actions;

        root = new ScrollView(context);
        root.setBackgroundColor(BG);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(28);
        column.setPadding(pad, pad, pad, pad);
        root.addView(column);

        TextView brand = new TextView(context);
        brand.setText("코너노트");
        brand.setTextColor(MUTED);
        brand.setTextSize(13f);
        brand.setLetterSpacing(0.1f);
        column.addView(brand);

        TextView title = new TextView(context);
        title.setText("이번 주행");
        title.setTextColor(TEXT);
        title.setTextSize(26f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        column.addView(title, topMargin(dp(4)));

        LinearLayout badges = new LinearLayout(context);
        badges.setOrientation(LinearLayout.HORIZONTAL);
        if (report.demoMode) {
            badges.addView(badge("데모 재생 중", "#3A2F6B"));
        }
        if (!report.locationAvailable) {
            badges.addView(badge("위치 없이 측정 중", "#4A3A1C"), leftMargin(dp(8)));
        }
        if (badges.getChildCount() > 0) {
            column.addView(badges, topMargin(dp(10)));
        }

        column.addView(summaryCard(), topMargin(dp(18)));
        column.addView(sectionTitle("거친 코너"), topMargin(dp(24)));

        if (report.topCorners.isEmpty()) {
            column.addView(noteCard("이번 주행에서는 코너가 검출되지 않았어요."), topMargin(dp(10)));
        } else {
            for (CoachSession.CornerFacts f : report.topCorners) {
                column.addView(cornerCard(f), topMargin(dp(10)));
            }
        }

        column.addView(sectionTitle("전비"), topMargin(dp(24)));
        column.addView(efficiencyCard(), topMargin(dp(10)));

        column.addView(prescriptionCard(), topMargin(dp(24)));
        column.addView(buttons(), topMargin(dp(20)));

        TextView foot = new TextView(context);
        foot.setText("가속도는 1초 창 기울기, 조향은 1.3초 창 기울기로 계산합니다. "
                + "브레이크는 밟음/뗌만 알 수 있어 전환 지연 불확도는 ±100ms 입니다."
                + (report.demoMode
                ? " 데모는 미리 정의된 주행 프로파일과 가상 GPS 좌표를 재생합니다." : ""));
        foot.setTextColor(Color.parseColor("#6B7480"));
        foot.setTextSize(11f);
        foot.setLineSpacing(dp(2), 1f);
        column.addView(foot, topMargin(dp(20)));
    }

    // ── 카드 ────────────────────────────────────────────────────────────
    private View summaryCard() {
        LinearLayout card = card(SURFACE, 14);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(stat("시간", report.durationText()), weight());
        card.addView(stat("거리", report.distanceMeasured
                ? String.format(Locale.US, "%.2f km", report.distanceKm)
                : "미측정"), weight());
        card.addView(stat("코너", report.cornerCount + "곳"), weight());
        return card;
    }

    private View stat(String label, String value) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView v = new TextView(context);
        v.setText(value);
        v.setTextColor(TEXT);
        v.setTextSize(19f);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        TextView l = new TextView(context);
        l.setText(label);
        l.setTextColor(MUTED);
        l.setTextSize(11f);
        box.addView(v);
        box.addView(l);
        return box;
    }

    private View cornerCard(CoachSession.CornerFacts f) {
        int color = severityColor(f.severity);
        LinearLayout card = card(SURFACE, 14);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(0, 0, dp(16), 0);

        View bar = new View(context);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(color);
        barBg.setCornerRadii(new float[]{dp(14), dp(14), 0, 0, 0, 0, dp(14), dp(14)});
        bar.setBackground(barBg);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(6),
                LinearLayout.LayoutParams.MATCH_PARENT);
        card.addView(bar, barLp);

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(14), 0, dp(14));

        TextView place = new TextView(context);
        place.setText(String.format(Locale.US, "코너 %d · %s", f.index, f.displayPlace()));
        place.setTextColor(MUTED);
        place.setTextSize(12f);
        body.addView(place);

        TextView label = new TextView(context);
        label.setText(f.labels.isEmpty() ? "양호" : joinTitles(f.labels, 2));
        label.setTextColor(color);
        label.setTextSize(18f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        body.addView(label, topMargin(dp(2)));

        TextView detail = new TextView(context);
        detail.setText(String.format(Locale.US, "진입 %.0f km/h · 조향 %.1f m/s³",
                f.entrySpeedMps * 3.6, f.steerIndexPeak));
        detail.setTextColor(Color.parseColor("#6B7480"));
        detail.setTextSize(11f);
        body.addView(detail, topMargin(dp(2)));

        CauseLabeler.Label top = CauseLabeler.topPrescription(f.labels);
        if (top != null) {
            TextView fix = new TextView(context);
            fix.setText(top.prescription);
            fix.setTextColor(TEXT);
            fix.setTextSize(14f);
            body.addView(fix, topMargin(dp(8)));
        }

        card.addView(body);
        return card;
    }

    private View efficiencyCard() {
        LinearLayout card = card(SURFACE, 14);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = new LinearLayout(context);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView v = new TextView(context);
        if (Double.isNaN(report.kmPerKwh)) {
            v.setText("측정 불가");
            v.setTextColor(MUTED);
        } else if (report.efficiencyReliable) {
            v.setText(String.format(Locale.US, "%.2f km/kWh", report.kmPerKwh));
            v.setTextColor(TEXT);
        } else {
            // SOC 1% 해상도로는 짧은 주행의 전비를 평가로 읽을 수 없다. 숫자는 숨기지 않되 표본 부족을 명시한다.
            v.setText(String.format(Locale.US, "%.2f km/kWh · 표본 부족", report.kmPerKwh));
            v.setTextColor(MUTED);
        }
        v.setTextSize(22f);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        left.addView(v);

        TextView sub = new TextView(context);
        if (!report.distanceMeasured) {
            sub.setText("주행거리 신호를 읽지 못했습니다 — 이 에뮬레이터는 주행거리를 갱신하지 않습니다");
        } else if (Double.isNaN(report.deltaSoc)) {
            sub.setText("SOC 변화를 읽지 못했습니다 (주행 중 SOC 가 변하지 않았거나 신호를 못 읽음)");
        } else if (!report.efficiencyReliable) {
            sub.setText(String.format(Locale.US,
                    "ΔSOC %.0f%% · %.2f kWh · %.2f km — SOC 1%% 해상도라 2km 이상 주행해야 전비를 평가할 수 있습니다",
                    report.deltaSoc, report.energyKwh, report.distanceKm));
        } else {
            sub.setText(String.format(Locale.US,
                    "ΔSOC %.0f%% · %.2f kWh · %.2f km (SOC 1%% 해상도라 짧은 주행은 오차가 큽니다)",
                    report.deltaSoc, report.energyKwh, report.distanceKm));
        }
        sub.setTextColor(MUTED);
        sub.setTextSize(11f);
        left.addView(sub, topMargin(dp(2)));
        card.addView(left, weight());
        return card;
    }

    private View prescriptionCard() {
        LinearLayout card = card(Color.parseColor("#16302A"), 14);
        card.setOrientation(LinearLayout.VERTICAL);

        TextView head = new TextView(context);
        head.setText("이번 주 처방 1개");
        head.setTextColor(Color.parseColor("#3FBF7F"));
        head.setTextSize(12f);
        head.setLetterSpacing(0.06f);
        card.addView(head);

        TextView body = new TextView(context);
        if (report.prescription != null) {
            body.setText(String.format(Locale.US, "코너 %d — %s",
                    report.prescriptionCorner, report.prescription.prescription));
        } else {
            body.setText("고칠 코너가 없었어요. 지금 리듬을 유지해 보세요.");
        }
        body.setTextColor(TEXT);
        body.setTextSize(17f);
        body.setTypeface(Typeface.DEFAULT_BOLD);
        body.setLineSpacing(dp(2), 1f);
        card.addView(body, topMargin(dp(6)));
        return card;
    }

    private View buttons() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button demo = new Button(context);
        demo.setText("데모 다시 재생");
        demo.setAllCaps(false);
        demo.setTextColor(TEXT);
        demo.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Color.parseColor("#2A2F3A")));
        demo.setOnClickListener(v -> actions.onRestartDemo());
        row.addView(demo, weight());

        Button close = new Button(context);
        close.setText("닫기");
        close.setAllCaps(false);
        close.setTextColor(Color.parseColor("#121417"));
        close.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        close.setOnClickListener(v -> actions.onClose());
        row.addView(close, leftMargin(dp(10), weight()));
        return row;
    }

    private View noteCard(String text) {
        LinearLayout card = card(SURFACE, 14);
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextColor(MUTED);
        t.setTextSize(14f);
        card.addView(t);
        return card;
    }

    // ── 유틸 ────────────────────────────────────────────────────────────
    /** 한 줄 라벨. 우선순위 상위 N개만 — 카드가 길어지지 않게. */
    private static String joinTitles(List<CauseLabeler.Label> labels, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < labels.size() && i < max; i++) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(labels.get(i).title);
        }
        if (labels.size() > max) {
            sb.append(" 외 ").append(labels.size() - max);
        }
        return sb.toString();
    }

    private static int severityColor(CauseLabeler.Severity s) {
        switch (s) {
            case BAD:
                return Color.parseColor("#F08A3C");
            case WARN:
                return Color.parseColor("#E8C547");
            default:
                return Color.parseColor("#3FBF7F");
        }
    }

    private LinearLayout card(int color, int radiusDp) {
        LinearLayout v = new LinearLayout(context);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radiusDp));
        v.setBackground(bg);
        v.setPadding(dp(16), dp(14), dp(16), dp(14));
        return v;
    }

    private TextView sectionTitle(String text) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextColor(TEXT);
        t.setTextSize(15f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView badge(String text, String color) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextColor(TEXT);
        t.setTextSize(11f);
        t.setPadding(dp(9), dp(3), dp(9), dp(3));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(color));
        bg.setCornerRadius(dp(9));
        t.setBackground(bg);
        return t;
    }

    private int dp(int v) {
        return DriveScreen.dp(context, v);
    }

    private static LinearLayout.LayoutParams topMargin(int margin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = margin;
        return p;
    }

    private static LinearLayout.LayoutParams leftMargin(int margin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.leftMargin = margin;
        return p;
    }

    private static LinearLayout.LayoutParams leftMargin(int margin, LinearLayout.LayoutParams inner) {
        inner.leftMargin = margin;
        return inner;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    public View view() {
        return root;
    }
}
