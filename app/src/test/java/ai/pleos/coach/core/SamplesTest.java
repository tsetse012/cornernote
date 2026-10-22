package ai.pleos.coach.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SamplesTest {

    /** dt 상한 1.0 적용 확인. */
    @Test
    public void clampsDtToUpperBound() {
        assertEquals(1.0, Samples.clampDt(1.5), 1e-12);
        assertEquals(1.0, Samples.clampDt(60.0), 1e-12);
        assertEquals(0.0219, Samples.clampDt(0.0219), 1e-12);
        assertEquals(0.0, Samples.clampDt(-0.5), 1e-12);
        assertEquals(0.0, Samples.clampDt(Double.NaN), 1e-12);
    }

    /** 벽시계 → 단조시계 정렬. */
    @Test
    public void convertsWallClockToElapsedAxis() {
        long wall = 1_792_652_511_014L;
        long elapsed = 7_656_546L;
        long offset = Samples.offsetFrom(wall, elapsed);
        assertEquals(elapsed, Samples.wallToElapsed(wall, offset));
        assertFalse(Samples.offsetDrifted(offset, offset + 500));
        assertTrue(Samples.offsetDrifted(offset, offset + 1500));
    }

    /** 링버퍼는 용량을 넘으면 가장 오래된 샘플을 버린다. */
    @Test
    public void ringKeepsNewestSamples() {
        Samples.Ring ring = new Samples.Ring(3);
        ring.add(100, 1.0);
        ring.add(200, 2.0);
        ring.add(300, 3.0);
        ring.add(400, 4.0);
        assertEquals(3, ring.size());
        assertEquals(200, ring.timeAt(0));
        assertEquals(4.0, ring.latestValue(), 1e-12);
        assertEquals(400, ring.latestTime());
    }
}
