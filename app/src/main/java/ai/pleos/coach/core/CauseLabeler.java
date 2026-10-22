package ai.pleos.coach.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 원인 라벨 + 처방 문구. 제품의 본체.
 *
 * <p>라벨은 코너 하나의 사실(facts)에서 나온다. 사실 계산은 {@link #factsOf} 가 한다.
 */
public final class CauseLabeler {

    public enum Label {
        LATE_DECEL("늦은 감속", "코너 2초 전에 감속을 시작하세요"),
        SLOW_TRANSITION("전환 지연 큼", "브레이크를 조금 남긴 채로 꺾으세요"),
        SHARP_STEER("조향 급함", "핸들을 두 번에 나눠서 드세요"),
        HARD_EXIT("탈출 가속 급함", "코너를 나오면서 천천히 밟으세요"),
        FAST_ENTRY("감속 부족", "코너 진입 전에 속도를 더 줄이세요");

        public final String title;
        public final String prescription;

        Label(String title, String prescription) {
            this.title = title;
            this.prescription = prescription;
        }
    }

    public enum Severity {
        GOOD("양호"), WARN("주의"), BAD("나쁨");

        public final String text;

        Severity(String text) {
            this.text = text;
        }
    }

    public static final double CONCENTRATION_THRESHOLD = 0.7;
    public static final double TRANSITION_THRESHOLD_SEC = 0.8;
    /** 상위 10% = 백분위 0.9 이상. */
    public static final double TOP_PERCENTILE = 0.9;

    /** 코너 하나를 판정하는 데 필요한 사실들. */
    public static final class Facts {
        public double decelConcentration = Double.NaN;
        public double transitionDelaySec = Double.NaN;
        public double steerPercentile = 0.0;
        public double exitAccelPercentile = 0.0;
        public boolean entrySpeedAboveAverage = false;

        public Facts() {
        }

        public Facts(double decelConcentration, double transitionDelaySec,
                     double steerPercentile, double exitAccelPercentile,
                     boolean entrySpeedAboveAverage) {
            this.decelConcentration = decelConcentration;
            this.transitionDelaySec = transitionDelaySec;
            this.steerPercentile = steerPercentile;
            this.exitAccelPercentile = exitAccelPercentile;
            this.entrySpeedAboveAverage = entrySpeedAboveAverage;
        }
    }

    private CauseLabeler() {
    }

    public static List<Label> labels(Facts f) {
        List<Label> out = new ArrayList<>(2);
        if (!Double.isNaN(f.decelConcentration)
                && f.decelConcentration > CONCENTRATION_THRESHOLD) {
            out.add(Label.LATE_DECEL);
        }
        if (!Double.isNaN(f.transitionDelaySec)
                && f.transitionDelaySec > TRANSITION_THRESHOLD_SEC) {
            out.add(Label.SLOW_TRANSITION);
        }
        if (f.steerPercentile >= TOP_PERCENTILE) {
            out.add(Label.SHARP_STEER);
        }
        if (f.exitAccelPercentile >= TOP_PERCENTILE) {
            out.add(Label.HARD_EXIT);
        }
        if (f.entrySpeedAboveAverage) {
            out.add(Label.FAST_ENTRY);
        }
        return out;
    }

    /** 라벨 0개 = 양호, 1개 = 주의, 2개 이상 = 나쁨. */
    public static Severity severity(List<Label> labels) {
        if (labels.isEmpty()) {
            return Severity.GOOD;
        }
        return labels.size() == 1 ? Severity.WARN : Severity.BAD;
    }

    /** "이번 주 처방 1개" — 가장 먼저 고칠 것 하나. */
    public static Label topPrescription(List<Label> labels) {
        if (labels.isEmpty()) {
            return null;
        }
        for (Label candidate : Label.values()) {
            if (labels.contains(candidate)) {
                return candidate;
            }
        }
        return labels.get(0);
    }
}
