package me.kenzierocks.a2m.v2;

import java.nio.DoubleBuffer;

public interface Window {

    double apply(int i, int nn);

    default double init_den(int len) {
        double den = 0;
        for (int i = 0; i < len; i++) {
            double apply = apply(i, len);
            den += apply * apply;
        }

        return den * len;
    }

    default void windowing(int len, DoubleBuffer data, double scale, DoubleBuffer out) {
        for (int i = 0; i < len; i++) {
            out.put(data.get() * apply(i, len) / scale);
        }
    }

}
