package ai.pleos.coach.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class CornerSegmenterTest {

    private static final double FS = 7.8;
    private static final long DT = Math.round(1000.0 / FS); // 128ms

    /** 조향 시계열에서 코너 2개를 검출한다. */
    @Test
    public void detectsTwoCorners() {
        CornerSegmenter seg = new CornerSegmenter();
        long t = 0;
        // 0~5초 직진
        for (int i = 0; i < 39; i++, t += DT) {
            seg.add(t, 0.0);
        }
        // 5~10초 오른쪽 코너 (25°)
        for (int i = 0; i < 39; i++, t += DT) {
            seg.add(t, 25.0);
        }
        // 10~15초 직진
        for (int i = 0; i < 39; i++, t += DT) {
            seg.add(t, 0.0);
        }
        // 15~20초 왼쪽 코너 (-20°)
        for (int i = 0; i < 39; i++, t += DT) {
            seg.add(t, -20.0);
        }
        // 20~25초 직진
        for (int i = 0; i < 39; i++, t += DT) {
            seg.add(t, 0.0);
        }
        seg.flush();

        List<CornerSegmenter.Corner> corners = seg.corners();
        assertEquals("코너 2개", 2, corners.size());
        assertEquals(1, corners.get(0).direction());
        assertEquals(-1, corners.get(1).direction());
        assertEquals(25.0, corners.get(0).peakSteerDeg, 1e-9);
        assertEquals(-20.0, corners.get(1).peakSteerDeg, 1e-9);
        assertTrue(corners.get(0).durationMillis() >= CornerSegmenter.MIN_DURATION_MS);
    }

    /** 2초 미만은 버린다 (차선변경 오검출 방지). */
    @Test
    public void discardsCornerShorterThanTwoSeconds() {
        CornerSegmenter seg = new CornerSegmenter();
        long t = 0;
        for (int i = 0; i < 39; i++, t += DT) {
            seg.add(t, 0.0);
        }
        // 1.5초짜리 조향 (15°)
        for (int i = 0; i < 12; i++, t += DT) {
            seg.add(t, 18.0);
        }
        for (int i = 0; i < 39; i++, t += DT) {
            seg.add(t, 0.0);
        }
        seg.flush();
        assertEquals(0, seg.corners().size());
    }

    /** 0.5초 미만 스파이크는 코너 시작이 아니다. */
    @Test
    public void ignoresBlipShorterThanSustain() {
        CornerSegmenter seg = new CornerSegmenter();
        long t = 0;
        for (int i = 0; i < 39; i++, t += DT) {
            seg.add(t, 0.0);
        }
        seg.add(t, 30.0);
        t += DT;
        seg.add(t, 30.0);
        t += DT;
        for (int i = 0; i < 39; i++, t += DT) {
            seg.add(t, 0.0);
        }
        seg.flush();
        assertEquals(0, seg.corners().size());
    }
}
