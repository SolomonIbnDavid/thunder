package thunder;

import haven.Astronomy;

/**
 * Lunar helpers for fishing-node peak flags. W16 nodes move every full moon.
 */
public final class FishingMoon {
    /** {@link Astronomy#phase} index of Full Moon. */
    public static final int FULL_INDEX = 4;

    private FishingMoon() {}

    public static int phaseIndex(double mp) {
	int n = Astronomy.phase.length;
	int i = (int) Math.round(mp * (double) n) % n;
	if(i < 0) {i += n;}
	return i;
    }

    public static String phaseName(double mp) {
	return Astronomy.phase[phaseIndex(mp)];
    }

    static double wrap01(double x) {
	x = x - Math.floor(x);
	if(x < 0) {x += 1;}
	return x;
    }

    /**
     * True if a Full Moon occurred in {@code (savedMp, nowMp]} along the
     * lunar cycle. A pin taken on a full moon stays fresh until the next one.
     */
    public static boolean staleAfterFullMoon(double savedMp, double nowMp) {
	if(Double.isNaN(savedMp) || Double.isNaN(nowMp)) {return false;}
	double s = wrap01(savedMp);
	double n = wrap01(nowMp);
	double ahead = n >= s ? n : n + 1.0;
	double full = (double) FULL_INDEX / (double) Astronomy.phase.length;
	double nextFull = s < full ? full : full + 1.0;
	return ahead >= nextFull;
    }

    public static String stamp(double mp, int hh, int mm) {
	return String.format("%s %02d:%02d", phaseName(mp), hh, mm);
    }
}
