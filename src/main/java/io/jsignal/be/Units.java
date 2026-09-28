package io.jsignal.be;

public final class Units {

    private static final double METRES_PER_FOOT = 0.3048;
    private static final double MPS_PER_KNOT = 0.514444;

    private Units() {
    }

    public static Float feetToMetres(Double feet) {
        return feet == null ? null : (float) (feet * METRES_PER_FOOT);
    }

    public static Float knotsToMps(Double knots) {
        return knots == null ? null : (float) (knots * MPS_PER_KNOT);
    }

    public static Integer metresToFeet(Float metres) {
        return metres == null ? null : (int) Math.round(metres / METRES_PER_FOOT);
    }

    public static Float mpsToKnots(Float mps) {
        return mps == null ? null : (float) (mps / MPS_PER_KNOT);
    }
}
