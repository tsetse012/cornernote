package ai.pleos.coach.core;

/**
 * 링버퍼·타임스탬프 정렬 유틸. Android 의존성 0.
 */
public final class Samples {

    /** 프레임 드랍이 미분값을 폭발시키지 않도록 dt 상한을 1.0초로 자른다. */
    public static final double MAX_DT_SEC = 1.0;

    private Samples() {
    }

    /** dt 를 (0, 1.0] 범위로 자른다. 0 이하/NaN 은 0 을 돌려준다(=샘플 버림). */
    public static double clampDt(double dtSec) {
        if (Double.isNaN(dtSec) || dtSec <= 0.0) {
            return 0.0;
        }
        return Math.min(dtSec, MAX_DT_SEC);
    }

    /** 앱 시작 시 1회 측정하는 벽시계↔단조시계 오프셋. */
    public static long offsetFrom(long wallNowMillis, long elapsedNowMillis) {
        return wallNowMillis - elapsedNowMillis;
    }

    /** 벽시계 타임스탬프를 단조시계 축으로 옮긴다. */
    public static long wallToElapsed(long wallMillis, long offsetMillis) {
        return wallMillis - offsetMillis;
    }

    /** 오프셋이 ±1초 넘게 흔들리면 재계산해야 한다. */
    public static boolean offsetDrifted(long offsetMillis, long newOffsetMillis) {
        return Math.abs(newOffsetMillis - offsetMillis) > 1000L;
    }

    /** 고정 용량 링버퍼. 0번 인덱스가 가장 오래된 샘플. */
    public static final class Ring {
        private final long[] times;
        private final double[] values;
        private int start;
        private int count;

        public Ring(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be > 0");
            }
            this.times = new long[capacity];
            this.values = new double[capacity];
        }

        public void clear() {
            start = 0;
            count = 0;
        }

        public int capacity() {
            return times.length;
        }

        public int size() {
            return count;
        }

        public boolean isFull() {
            return count == times.length;
        }

        public void add(long timestampMillis, double value) {
            int slot = (start + count) % times.length;
            times[slot] = timestampMillis;
            values[slot] = value;
            if (count < times.length) {
                count++;
            } else {
                start = (start + 1) % times.length;
            }
        }

        private int index(int i) {
            if (i < 0 || i >= count) {
                throw new IndexOutOfBoundsException("i=" + i + " size=" + count);
            }
            return (start + i) % times.length;
        }

        public long timeAt(int i) {
            return times[index(i)];
        }

        public double valueAt(int i) {
            return values[index(i)];
        }

        public long latestTime() {
            return timeAt(count - 1);
        }

        public double latestValue() {
            return valueAt(count - 1);
        }

        /** 가장 최근 샘플에서 과거로 최대 windowMillis 까지의 구간을 새 배열로 복사한다. */
        public int[] rangeWithin(long windowMillis) {
            if (count == 0) {
                return new int[0];
            }
            long newest = latestTime();
            int from = count - 1;
            while (from > 0 && newest - timeAt(from - 1) <= windowMillis) {
                from--;
            }
            int[] out = new int[count - from];
            for (int i = 0; i < out.length; i++) {
                out[i] = from + i;
            }
            return out;
        }
    }
}
