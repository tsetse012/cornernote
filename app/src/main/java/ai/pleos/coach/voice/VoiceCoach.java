package ai.pleos.coach.voice;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import ai.pleos.playground.tts.TextToSpeech;
import ai.pleos.playground.tts.constant.Mode;
import ai.pleos.playground.tts.listener.OnInitializeListener;

/**
 * TTS. 초기화 실패 시 조용히 비활성 — 앱은 계속 동작한다.
 *
 * <p>톤 규칙: 미래 지향 1문장, 하루 최대 2회, 최소 간격 10분.
 */
public final class VoiceCoach {

    private static final String TAG = "CornerNote";
    private static final String PREFS = "voice";
    private static final String KEY_DAY = "day";
    private static final String KEY_COUNT = "count";
    private static final String KEY_LAST = "last_millis";
    public static final int MAX_PER_DAY = 2;
    public static final long MIN_GAP_MILLIS = 10L * 60L * 1000L;

    private TextToSpeech tts;
    private boolean ready;
    private String failureReason;

    public boolean init(Context context) {
        try {
            tts = new TextToSpeech(context, Mode.ON_DEVICE);
            tts.initialize(new OnInitializeListener() {
                @Override
                public void onSuccess() {
                    ready = true;
                    Log.i(TAG, "tts ready (ON_DEVICE)");
                }

                @Override
                public void onFailure(ai.pleos.playground.tts.constant.ErrorType error) {
                    ready = false;
                    failureReason = "init failure: " + error;
                    Log.w(TAG, "tts init failed: " + error);
                }
            });
            return true;
        } catch (Throwable t) {
            failureReason = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "tts unavailable: " + failureReason);
            tts = null;
            return false;
        }
    }

    public boolean isReady() {
        return ready;
    }

    public String failureReason() {
        return failureReason;
    }

    /**
     * 하루 2회·10분 간격 제한을 지켜 1문장을 말한다.
     *
     * @return 실제로 발화했는지
     */
    public boolean speak(Context context, String sentence) {
        if (!ready || tts == null) {
            return false;
        }
        if (!allowNow(context)) {
            Log.i(TAG, "voice skipped by rate limit");
            return false;
        }
        try {
            tts.speak(sentence, true);
            record(context);
            Log.i(TAG, "tts spoke: " + sentence);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "tts speak failed: " + t);
            return false;
        }
    }

    private boolean allowNow(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String today = todayKey();
        String day = p.getString(KEY_DAY, "");
        int count = day.equals(today) ? p.getInt(KEY_COUNT, 0) : 0;
        if (count >= MAX_PER_DAY) {
            return false;
        }
        long last = p.getLong(KEY_LAST, 0L);
        long now = System.currentTimeMillis();
        return now - last >= MIN_GAP_MILLIS || last == 0L;
    }

    private void record(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String today = todayKey();
        String day = p.getString(KEY_DAY, "");
        int count = day.equals(today) ? p.getInt(KEY_COUNT, 0) : 0;
        p.edit()
                .putString(KEY_DAY, today)
                .putInt(KEY_COUNT, count + 1)
                .putLong(KEY_LAST, System.currentTimeMillis())
                .apply();
    }

    private static String todayKey() {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        return f.format(new java.util.Date());
    }

    public void release() {
        try {
            if (tts != null) {
                tts.release();
            }
        } catch (Throwable ignored) {
        }
        tts = null;
        ready = false;
    }
}
