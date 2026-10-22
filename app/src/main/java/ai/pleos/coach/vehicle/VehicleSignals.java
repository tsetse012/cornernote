package ai.pleos.coach.vehicle;

import android.content.Context;
import android.util.Log;

import ai.pleos.playground.vehicle.Vehicle;

/**
 * Pleos Vehicle SDK 초기화만 담당한다. 신호별 등록은 각 Source 가 자기 try/catch 로 한다.
 *
 * <p>스펙 §9: {@code isAvailable()} 하나로 전체 모드를 바꾸지 않는다. 신호별 개별 확인.
 */
public final class VehicleSignals {

    private static final String TAG = "CornerNote";

    private Vehicle vehicle;
    private String failureReason;

    /** @return Vehicle 초기화 성공 여부. 실패해도 앱은 계속 돈다. */
    public boolean init(Context context) {
        try {
            vehicle = new Vehicle(context);
            vehicle.initialize();
            return true;
        } catch (Throwable t) {
            failureReason = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "vehicle init failed: " + failureReason);
            vehicle = null;
            return false;
        }
    }

    public Vehicle vehicle() {
        return vehicle;
    }

    public String failureReason() {
        return failureReason;
    }

    public void release() {
        try {
            if (vehicle != null) {
                vehicle.release();
            }
        } catch (Throwable ignored) {
        }
        vehicle = null;
    }
}
