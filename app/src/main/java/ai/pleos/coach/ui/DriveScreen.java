package ai.pleos.coach.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * 주행 중 화면 (스펙 §6-1).
 *
 * <p>화면 요소 최소: 상태 한 줄 + 코너 카운트. 움직이는 이미지·자동 스크롤 없음.
 */
public final class DriveScreen {

    private final LinearLayout root;
    private final TextView status;
    private final TextView counter;
    private final TextView speed;
    private final TextView badgeDemo;
    private final TextView badgeLocation;

    public DriveScreen(Context context) {
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#121417"));
        int pad = dp(context, 32);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView brand = new TextView(context);
        brand.setText("코너노트");
        brand.setTextColor(Color.parseColor("#9AA3AE"));
        brand.setTextSize(16f);
        brand.setLetterSpacing(0.08f);
        root.addView(brand, lp(-2, -2, 0, dp(context, 4)));

        status = new TextView(context);
        status.setText("주행을 기다리는 중");
        status.setTextColor(Color.parseColor("#F2F4F7"));
        status.setTextSize(22f);
        root.addView(status, lp(-2, -2, 0, dp(context, 24)));

        counter = new TextView(context);
        counter.setText("코너 0곳");
        counter.setTextColor(Color.parseColor("#5B9DFF"));
        counter.setTextSize(40f);
        counter.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(counter, lp(-2, -2, 0, dp(context, 8)));

        speed = new TextView(context);
        speed.setText("0 km/h");
        speed.setTextColor(Color.parseColor("#9AA3AE"));
        speed.setTextSize(16f);
        root.addView(speed, lp(-2, -2, 0, dp(context, 24)));

        LinearLayout badges = new LinearLayout(context);
        badges.setOrientation(LinearLayout.HORIZONTAL);
        badgeDemo = badge(context, "데모 재생 중", "#3A2F6B");
        badgeLocation = badge(context, "위치 없이 측정 중", "#4A3A1C");
        badges.addView(badgeDemo);
        badges.addView(badgeLocation, lp(-2, -2, dp(context, 10), 0));
        root.addView(badges, lp(-2, -2, 0, dp(context, 20)));

        TextView hint = new TextView(context);
        hint.setText("정차하면 리포트가 나옵니다");
        hint.setTextColor(Color.parseColor("#6B7480"));
        hint.setTextSize(13f);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = lp(-1, -2, 0, 0);
        hintLp.topMargin = dp(context, 32);
        root.addView(hint, hintLp);
    }

    private static LinearLayout.LayoutParams lp(int w, int h, int leftMargin, int topMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.leftMargin = leftMargin;
        p.topMargin = topMargin;
        return p;
    }

    private static TextView badge(Context context, String text, String color) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextColor(Color.parseColor("#F2F4F7"));
        t.setTextSize(12f);
        int ph = dp(context, 10);
        int pv = dp(context, 4);
        t.setPadding(ph, pv, ph, pv);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(color));
        bg.setCornerRadius(dp(context, 10));
        t.setBackground(bg);
        t.setVisibility(View.GONE);
        return t;
    }

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public View view() {
        return root;
    }

    public void update(String statusText, int cornerCount, double speedKph,
                       boolean demo, boolean locationMissing) {
        status.setText(statusText);
        counter.setText(String.format(Locale.US, "코너 %d곳", cornerCount));
        speed.setText(String.format(Locale.US, "%.0f km/h", speedKph));
        badgeDemo.setVisibility(demo ? View.VISIBLE : View.GONE);
        badgeLocation.setVisibility(locationMissing ? View.VISIBLE : View.GONE);
    }
}
