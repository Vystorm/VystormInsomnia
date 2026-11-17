package at.vystorm.insomnia.sleep;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Time acceleration maths. {@code speed} is game ticks per server tick: 1.0 is normal, 6.0 lets the clock run six
 * times as fast. With {@code s} sleeping of {@code n} counted players the share is {@code p = s / n} and
 * <pre>speed = min + (max - min) * p^curve</pre>
 * so the first sleeper already gives {@code min} (for one player alone: {@code max}), everyone asleep gives
 * {@code max}. {@code curve} 1.0 is linear, above 1 stays slow until most players sleep, below 1 is fast early.
 */
public final class SleepSpeed {
    public static final double LIMIT = 100.0;

    private SleepSpeed() {}

    public static double speed(int sleeping, int players, double min, double max, double curve) {
        if (sleeping <= 0 || players <= 0) return 1.0;
        double low = clampSpeed(min);
        double high = Math.max(low, clampSpeed(max));
        double share = Math.min(1.0, (double) sleeping / players);
        double exponent = Double.isFinite(curve) && curve > 0 ? curve : 1.0;
        return low + (high - low) * Math.pow(share, exponent);
    }

    static double clampSpeed(double value) {
        if (!Double.isFinite(value)) return 1.0;
        return Math.max(1.0, Math.min(LIMIT, value));
    }

    /** Speed for the action bar: "4.5" / "4,5" below ten, whole numbers above. */
    public static String label(double speed, Locale locale) {
        DecimalFormat format = new DecimalFormat(speed >= 10.0 ? "0" : "0.#", DecimalFormatSymbols.getInstance(locale));
        return format.format(speed);
    }

    /**
     * Turns a fractional speed into whole extra ticks per server tick (vanilla adds the normal one). The remainder
     * is carried, so 2.5x adds 1, 2, 1, 2 ... extra ticks. Server thread only.
     */
    public static final class Carry {
        private double carry;

        /**
         * @param remaining ticks left until the wake-up target; the result never overshoots it
         * @return extra ticks to add this tick
         */
        public long extra(double speed, long remaining) {
            if (!(speed > 1.0) || remaining <= 0) { carry = 0; return 0; }
            carry += Math.min(LIMIT, speed) - 1.0;
            long whole = (long) Math.floor(carry);
            carry -= whole;
            return Math.min(whole, remaining);
        }

        public void reset() { carry = 0; }
    }
}
