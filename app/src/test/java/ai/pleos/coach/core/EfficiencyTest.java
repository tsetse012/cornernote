package ai.pleos.coach.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EfficiencyTest {

    /** ΔSOC 10%, 용량 77.4kWh, 거리 5km → 기대값 검증. */
    @Test
    public void computesKmPerKwhFromSocDrop() {
        double capacityWh = 77400.0;   // 77.4 kWh
        double energy = Efficiency.energyKwh(10.0, capacityWh);
        assertEquals(7.74, energy, 1e-9);

        double kmPerKwh = Efficiency.kmPerKwh(5.0, energy);
        assertEquals(5.0 / 7.74, kmPerKwh, 1e-9);

        double direct = Efficiency.kmPerKwhFromSoc(80.0, 70.0, capacityWh, 5.0);
        assertEquals(5.0 / 7.74, direct, 1e-9);
    }

    /** 차량이 kWh 로 주면 Wh 로 환산한다. */
    @Test
    public void normalizesCapacityUnits() {
        assertEquals(77400.0, Efficiency.normalizeCapacityWh(77.4), 1e-9);
        assertEquals(77400.0, Efficiency.normalizeCapacityWh(77400.0), 1e-9);
        assertEquals(Efficiency.DEFAULT_CAPACITY_WH, Efficiency.normalizeCapacityWh(Double.NaN), 1e-9);
        assertEquals(Efficiency.DEFAULT_CAPACITY_WH, Efficiency.normalizeCapacityWh(0.0), 1e-9);
    }

    /** 계산 불가 조건은 NaN. */
    @Test
    public void returnsNaNWhenUndefined() {
        assertTrue(Double.isNaN(Efficiency.kmPerKwh(5.0, 0.0)));
        assertTrue(Double.isNaN(Efficiency.kmPerKwh(0.0, 7.74)));
        assertTrue(Double.isNaN(Efficiency.kmPerKwhFromSoc(70.0, 70.0, 77400.0, 5.0)));
    }
}
