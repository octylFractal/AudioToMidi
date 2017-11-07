package me.kenzierocks.a2m.v2;

import java.nio.DoubleBuffer;

public class HC {

    public static void to_polar2(int len, DoubleBuffer freq, int conj, double scale, double[] amp2, double[] phs) {
        int i;
        double rl, im;

        phs[0] = 0.0;
        amp2[0] = freq.get(0) * freq.get(0) / scale;
        for (i = 1; i < (len + 1) / 2; i++) {
            rl = freq.get(i);
            im = freq.get(len - i);
            amp2[i] = (rl * rl + im * im) / scale;
            if (amp2[i] > 0.0) {
                if (conj == 0)
                    phs[i] = Math.atan2(+im, rl);
                else
                    phs[i] = Math.atan2(-im, rl);
            } else {
                phs[i] = 0.0;
            }
        }
        if (len % 2 == 0) {
            phs[len / 2] = 0.0;
            amp2[len / 2] = freq.get(len / 2) * freq.get(len / 2) / scale;
        }
    }

}
