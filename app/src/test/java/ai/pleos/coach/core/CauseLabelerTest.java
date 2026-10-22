package ai.pleos.coach.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class CauseLabelerTest {

    private static CauseLabeler.Facts facts(double conc, double delay,
                                            double steerPct, double exitPct, boolean fastEntry) {
        return new CauseLabeler.Facts(conc, delay, steerPct, exitPct, fastEntry);
    }

    @Test
    public void lateDecelerationIsLabelled() {
        List<CauseLabeler.Label> labels = CauseLabeler.labels(facts(0.82, Double.NaN, 0.5, 0.5, false));
        assertEquals(1, labels.size());
        assertEquals(CauseLabeler.Label.LATE_DECEL, labels.get(0));
        assertEquals("코너 2초 전에 감속을 시작하세요", labels.get(0).prescription);
    }

    @Test
    public void slowTransitionIsLabelled() {
        List<CauseLabeler.Label> labels = CauseLabeler.labels(facts(0.3, 1.2, 0.5, 0.5, false));
        assertEquals(1, labels.size());
        assertEquals(CauseLabeler.Label.SLOW_TRANSITION, labels.get(0));
        assertEquals("브레이크를 조금 남긴 채로 꺾으세요", labels.get(0).prescription);
    }

    @Test
    public void sharpSteeringIsLabelled() {
        List<CauseLabeler.Label> labels = CauseLabeler.labels(facts(0.3, Double.NaN, 0.95, 0.5, false));
        assertEquals(1, labels.size());
        assertEquals(CauseLabeler.Label.SHARP_STEER, labels.get(0));
        assertEquals("핸들을 두 번에 나눠서 드세요", labels.get(0).prescription);
    }

    @Test
    public void multipleCausesRaiseSeverityAndPickOnePrescription() {
        List<CauseLabeler.Label> labels = CauseLabeler.labels(facts(0.9, 1.4, 0.97, 0.5, false));
        assertEquals(3, labels.size());
        assertEquals(CauseLabeler.Severity.BAD, CauseLabeler.severity(labels));
        assertEquals(CauseLabeler.Label.LATE_DECEL, CauseLabeler.topPrescription(labels));
    }

    @Test
    public void cleanCornerIsGoodWithNoPrescription() {
        List<CauseLabeler.Label> labels = CauseLabeler.labels(facts(0.2, 0.3, 0.1, 0.1, false));
        assertTrue(labels.isEmpty());
        assertEquals(CauseLabeler.Severity.GOOD, CauseLabeler.severity(labels));
        assertNull(CauseLabeler.topPrescription(labels));
    }
}
