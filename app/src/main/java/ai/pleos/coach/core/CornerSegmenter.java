package ai.pleos.coach.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 조향 기반 코너 구간 검출.
 *
 * <ul>
 *   <li>|조향| &gt; 15° 가 0.5초 이상 지속 → 코너 시작</li>
 *   <li>|조향| &lt; 5° 가 0.5초 이상 지속 → 코너 종료</li>
 *   <li>코너 사이 최소 간격 1.5초 (히스테리시스)</li>
 *   <li>2초 미만 코너는 버린다 (차선변경·회전교차로 오검출 방지)</li>
 * </ul>
 */
public final class CornerSegmenter {

    public static final double ENTER_DEG = 15.0;
    public static final double EXIT_DEG = 5.0;
    public static final long SUSTAIN_MS = 500L;
    public static final long MIN_GAP_MS = 1500L;
    public static final long MIN_DURATION_MS = 2000L;

    public static final class Corner {
        public final long startMillis;
        public final long endMillis;
        public final double peakSteerDeg;

        public Corner(long startMillis, long endMillis, double peakSteerDeg) {
            this.startMillis = startMillis;
            this.endMillis = endMillis;
            this.peakSteerDeg = peakSteerDeg;
        }

        public long durationMillis() {
            return endMillis - startMillis;
        }

        /** +1 = 오른쪽(양수 조향), -1 = 왼쪽. */
        public int direction() {
            return peakSteerDeg >= 0 ? 1 : -1;
        }

        @Override
        public String toString() {
            return "Corner[" + startMillis + ".." + endMillis + " peak=" + peakSteerDeg + "]";
        }
    }

    private enum State { STRAIGHT, ENTERING, IN_CORNER, EXITING }

    private final List<Corner> corners = new ArrayList<>();
    private State state = State.STRAIGHT;
    private long candidateSince;
    private long cornerStart;
    private long releaseSince;
    private long lastCornerEnd = Long.MIN_VALUE;
    private double peak;
    private long lastSeen;

    public void add(long timeMillis, double steerDeg) {
        lastSeen = timeMillis;
        double abs = Math.abs(steerDeg);
        switch (state) {
            case STRAIGHT:
                if (abs > ENTER_DEG) {
                    if (lastCornerEnd != Long.MIN_VALUE && timeMillis - lastCornerEnd < MIN_GAP_MS) {
                        return; // 최소 간격 미충족
                    }
                    state = State.ENTERING;
                    candidateSince = timeMillis;
                    peak = steerDeg;
                }
                break;
            case ENTERING:
                if (Math.abs(steerDeg) > Math.abs(peak)) {
                    peak = steerDeg;
                }
                if (abs < ENTER_DEG) {
                    state = State.STRAIGHT;
                } else if (timeMillis - candidateSince >= SUSTAIN_MS) {
                    state = State.IN_CORNER;
                    cornerStart = candidateSince;
                }
                break;
            case IN_CORNER:
                if (Math.abs(steerDeg) > Math.abs(peak)) {
                    peak = steerDeg;
                }
                if (abs < EXIT_DEG) {
                    state = State.EXITING;
                    releaseSince = timeMillis;
                }
                break;
            case EXITING:
                if (abs > ENTER_DEG) {
                    state = State.IN_CORNER;
                } else if (timeMillis - releaseSince >= SUSTAIN_MS) {
                    closeCorner(releaseSince);
                }
                break;
        }
    }

    private void closeCorner(long endMillis) {
        if (endMillis - cornerStart >= MIN_DURATION_MS) {
            corners.add(new Corner(cornerStart, endMillis, peak));
            lastCornerEnd = endMillis;
        }
        state = State.STRAIGHT;
        peak = 0.0;
    }

    /** 세션이 끝났을 때 열려 있는 코너를 닫는다. */
    public void flush() {
        if (state == State.IN_CORNER || state == State.EXITING) {
            long end = state == State.EXITING ? releaseSince : lastSeen;
            closeCorner(end);
        }
        state = State.STRAIGHT;
    }

    public List<Corner> corners() {
        return corners;
    }

    /** 확정된 코너 수 (주행 중 화면의 카운터). */
    public int confirmedCount() {
        return corners.size();
    }

    public void reset() {
        corners.clear();
        state = State.STRAIGHT;
        lastCornerEnd = Long.MIN_VALUE;
        peak = 0.0;
    }
}
