package ai.pleos.coach.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class EventDetectorTest {

    /** 단발 스파이크 1개는 이벤트가 아니다 (지속 0.5초 미만). */
    @Test
    public void singleSpikeIsNotAnEvent() {
        EventDetector det = EventDetector.deceleration();
        long t = 0;
        // 정상 주행
        for (int i = 0; i < 100; i++, t += 22) {
            det.add(t, -0.3);
        }
        // 1 km/h 양자화가 만드는 단발 스파이크
        det.add(t, -3.2);
        t += 22;
        for (int i = 0; i < 100; i++, t += 22) {
            det.add(t, -0.3);
        }
        det.flush();
        assertEquals(0, det.events().size());
    }

    /** 1초 지속은 이벤트 1개다 (여러 개가 아니다). */
    @Test
    public void oneSecondSustainedIsExactlyOneEvent() {
        EventDetector det = EventDetector.deceleration();
        long t = 0;
        for (int i = 0; i < 50; i++, t += 22) {
            det.add(t, -0.2);
        }
        long triggerAt = t;
        // 1초 지속 감속 (-2.9)
        for (int i = 0; i < 45; i++, t += 22) {
            det.add(t, -2.9);
        }
        // 해제
        for (int i = 0; i < 60; i++, t += 22) {
            det.add(t, -0.2);
        }
        det.flush();
        List<EventDetector.Event> events = det.events();
        assertEquals(1, events.size());
        assertEquals(triggerAt, events.get(0).startMillis);
        assertTrue("피크는 -2.9 근처", Math.abs(events.get(0).peak + 2.9) < 1e-6);
    }

    /** 히스테리시스: 해제 임계(-2.0)를 잠깐 넘었다가 돌아오면 같은 이벤트로 이어진다. */
    @Test
    public void hysteresisKeepsEventTogether() {
        EventDetector det = EventDetector.deceleration();
        long t = 0;
        for (int i = 0; i < 45; i++, t += 22) {
            det.add(t, -3.0);
        }
        // 0.2초만 -1.9 (해제 임계 위)
        for (int i = 0; i < 9; i++, t += 22) {
            det.add(t, -1.9);
        }
        // 다시 강한 감속
        for (int i = 0; i < 45; i++, t += 22) {
            det.add(t, -3.0);
        }
        for (int i = 0; i < 60; i++, t += 22) {
            det.add(t, -0.2);
        }
        det.flush();
        assertEquals(1, det.events().size());
    }
}
