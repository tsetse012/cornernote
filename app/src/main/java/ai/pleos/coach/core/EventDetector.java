package ai.pleos.coach.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 지속시간 + 비대칭 해제 디바운스.
 *
 * <p>1 km/h 양자화는 같은 제동 1회를 "단발로 여러 번 찍히는" 신호로 만든다.
 * 프레임 수로 세면 제동 1회가 이벤트 5개가 되므로,
 * <ul>
 *   <li>트리거 임계를 0.5초 이상 지속해야 이벤트 확정</li>
 *   <li>해제는 트리거보다 완화된 임계에서 0.5초 지속해야 확정</li>
 * </ul>
 */
public final class EventDetector {

    public static final long MIN_DURATION_MS = 500L;

    public enum Direction {
        /** 값이 임계보다 작아지면 이벤트 (감속). */
        BELOW,
        /** 값이 임계보다 커지면 이벤트 (가속). */
        ABOVE
    }

    /** 확정된 이벤트 하나. */
    public static final class Event {
        public final long startMillis;
        public final long endMillis;
        public final double peak;

        public Event(long startMillis, long endMillis, double peak) {
            this.startMillis = startMillis;
            this.endMillis = endMillis;
            this.peak = peak;
        }

        public long durationMillis() {
            return endMillis - startMillis;
        }

        @Override
        public String toString() {
            return "Event[" + startMillis + ".." + endMillis + " peak=" + peak + "]";
        }
    }

    private enum State { IDLE, CANDIDATE, ACTIVE, RELEASING }

    private final double trigger;
    private final double release;
    private final Direction direction;
    private final List<Event> events = new ArrayList<>();

    private State state = State.IDLE;
    private long candidateSince;
    private long activeSince;
    private long releaseSince;
    private double peak;
    private long lastSeen;

    public EventDetector(double triggerThreshold, double hysteresis, Direction direction) {
        this.trigger = triggerThreshold;
        this.release = direction == Direction.BELOW
                ? triggerThreshold + Math.abs(hysteresis)
                : triggerThreshold - Math.abs(hysteresis);
        this.direction = direction;
    }

    public static EventDetector deceleration() {
        return new EventDetector(Metrics.DECEL_TRIGGER, 0.5, Direction.BELOW);
    }

    private boolean isTriggered(double value) {
        return direction == Direction.BELOW ? value <= trigger : value >= trigger;
    }

    private boolean isReleased(double value) {
        return direction == Direction.BELOW ? value >= release : value <= release;
    }

    private boolean isBeyondPeak(double value) {
        return direction == Direction.BELOW ? value < peak : value > peak;
    }

    public void add(long timeMillis, double value) {
        lastSeen = timeMillis;
        switch (state) {
            case IDLE:
                if (isTriggered(value)) {
                    state = State.CANDIDATE;
                    candidateSince = timeMillis;
                    peak = value;
                }
                break;
            case CANDIDATE:
                if (isBeyondPeak(value)) {
                    peak = value;
                }
                if (isReleased(value)) {
                    // 0.5초를 못 채우고 돌아왔다 → 단발 스파이크, 이벤트 아님
                    state = State.IDLE;
                } else if (timeMillis - candidateSince >= MIN_DURATION_MS) {
                    state = State.ACTIVE;
                    activeSince = candidateSince;
                }
                break;
            case ACTIVE:
                if (isBeyondPeak(value)) {
                    peak = value;
                }
                if (isReleased(value)) {
                    state = State.RELEASING;
                    releaseSince = timeMillis;
                }
                break;
            case RELEASING:
                if (isTriggered(value)) {
                    // 다시 임계 안으로 → 같은 이벤트가 이어진다
                    state = State.ACTIVE;
                } else if (timeMillis - releaseSince >= MIN_DURATION_MS) {
                    events.add(new Event(activeSince, releaseSince, peak));
                    state = State.IDLE;
                    peak = 0.0;
                }
                break;
        }
    }

    /** 이벤트가 열려 있는 채로 세션이 끝났을 때 마지막 샘플 시각으로 닫는다. */
    public void flush() {
        if (state == State.ACTIVE || state == State.RELEASING) {
            long end = state == State.RELEASING ? releaseSince : lastSeen;
            events.add(new Event(activeSince, end, peak));
        }
        state = State.IDLE;
        peak = 0.0;
    }

    public List<Event> events() {
        return events;
    }

    public boolean isActive() {
        return state == State.ACTIVE || state == State.RELEASING;
    }

    public void reset() {
        events.clear();
        state = State.IDLE;
        peak = 0.0;
    }
}
