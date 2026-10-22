package ai.pleos.coach;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ai.pleos.coach.core.CauseLabeler;
import ai.pleos.coach.demo.DemoProfile;

/**
 * 데모 프로파일을 실제 파이프라인에 그대로 흘려 코너 3개와 원인 라벨이 나오는지 본다.
 * 에뮬레이터 없이 도는 회귀 테스트.
 */
public class DemoReplayTest {

    private static CoachSession.Report replay() {
        DemoProfile profile = new DemoProfile();
        CoachSession session = new CoachSession(true);
        session.markLocationVirtual();

        long base = 1_000_000L;
        int lastSteer = -1;
        int lastBrake = -1;
        int lastSec = -1;

        for (int ms = 0; ms <= 60_000; ms += 22) {
            long now = base + ms;
            double tSec = ms / 1000.0;
            session.onSpeed(now, (float) profile.speedMps(tSec));

            int steerIdx = (int) (tSec * 7.8);
            if (steerIdx != lastSteer) {
                lastSteer = steerIdx;
                session.onSteer(now, (float) profile.steerDeg(tSec));
            }
            int brakeIdx = (int) (tSec * 13.0);
            if (brakeIdx != lastBrake) {
                lastBrake = brakeIdx;
                session.onBrake(now, profile.brakePressed(tSec));
            }
            int secIdx = (int) tSec;
            if (secIdx != lastSec) {
                lastSec = secIdx;
                session.onEnergy(now, profile.socPercent(tSec), 77400f,
                        (float) profile.distanceKm(tSec));
            }
        }
        return session.finish();
    }

    @Test
    public void demoProducesThreeCorners() {
        CoachSession.Report report = replay();
        assertEquals("코너 3곳", 3, report.cornerCount);
        assertEquals(3, report.corners.size());
    }

    @Test
    public void everyMetricHasANonZeroValue() {
        CoachSession.Report report = replay();
        assertTrue("가속도 평균", report.accelMeanAbs > 0.05);
        assertTrue("가속도 p95", report.accelP95Abs > 0.5);
        assertTrue("조향 지수", report.steerIndexMean > 0.1);
        assertTrue("전환 지연", report.transitionSamples > 0
                && !Double.isNaN(report.transitionDelaySec));
        assertTrue("전비", !Double.isNaN(report.kmPerKwh) && report.kmPerKwh > 0);
    }

    /** 급조향 코너(3번)는 '조향 급함'이 잡혀야 한다. */
    @Test
    public void sharpCornerIsLabelledSharpSteering() {
        CoachSession.Report report = replay();
        CoachSession.CornerFacts sharp = report.corners.get(2);
        assertTrue("코너 3 라벨=" + sharp.labels,
                sharp.labels.contains(CauseLabeler.Label.SHARP_STEER));
        assertEquals(CauseLabeler.Severity.WARN, sharp.severity);
    }

    /** 늦은 감속 코너(1번)는 감속 집중도가 0.7을 넘고 '늦은 감속' 라벨이 붙는다. */
    @Test
    public void firstCornerIsLabelledLateDeceleration() {
        CoachSession.Report report = replay();
        CoachSession.CornerFacts first = report.corners.get(0);
        assertTrue("감속 집중도=" + first.decelConcentration,
                first.decelConcentration > CauseLabeler.CONCENTRATION_THRESHOLD);
        assertTrue("라벨=" + first.labels,
                first.labels.contains(CauseLabeler.Label.LATE_DECEL));
    }

    /** 정상 코너(2번)는 양호로 남는다 — 라벨이 붙지 않는다. */
    @Test
    public void secondCornerStaysClean() {
        CoachSession.Report report = replay();
        CoachSession.CornerFacts second = report.corners.get(1);
        assertTrue("라벨=" + second.labels, second.labels.isEmpty());
        assertEquals(CauseLabeler.Severity.GOOD, second.severity);
    }

    /** 데모는 프로파일에서 거리를 계산해 넘기므로 "측정됨"이어야 한다. */
    @Test
    public void demoDistanceIsMeasured() {
        CoachSession.Report report = replay();
        assertTrue("거리 측정 여부", report.distanceMeasured);
        assertTrue("거리 " + report.distanceKm, report.distanceKm > 0.5);
    }

    /** 처방은 가장 먼저 고칠 하나 — 이 데모에서는 코너 1의 늦은 감속. */
    @Test
    public void prescriptionPointsAtFirstCorner() {
        CoachSession.Report report = replay();
        assertEquals(CauseLabeler.Label.LATE_DECEL, report.prescription);
        assertEquals(1, report.prescriptionCorner);
    }

    /** 처방은 정확히 1개, 거친 코너는 최대 3개. */
    @Test
    public void reportHasSinglePrescriptionAndTopThree() {
        CoachSession.Report report = replay();
        assertTrue(report.topCorners.size() <= 3);
        assertTrue("처방이 있어야 한다", report.prescription != null);
    }
}
