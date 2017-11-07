/*
 * This file is part of AudioToMidi, licensed under the MIT License (MIT).
 *
 * Copyright (c) TechShroom Studios <https://techshroom.com>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package me.kenzierocks.a2m.v2;

import me.kenzierocks.a2m.MidiFreqRelations;

public class Analyze {

    public static void note_intensity(double[] p, double[] fp, double cut_ratio,
            double rel_cut_ratio, int i0, int i1, double t0, byte[] intens) {

        int i;
        int imax;
        double max;
        double x;
        double freq; /* freq of peak in power */
        double f;
        int in;
        double av;

        // clear
        for (i = 0; i < 128; i++) {
            intens[i] = 0;
        }

        // calc average power
        if (!Extern.abs_flg) {
            av = 0.0;
            for (i = i0; i < i1; i++) {
                av += p[i];
            }
            av /= (double) (i1 - i0);
        } else {
            av = 1.0;
        }

        for (;;) {
            // search peak
            // set the threshold to the average
            if (!Extern.abs_flg) {
                max = av * Math.pow(10.0, rel_cut_ratio);
            } else {
                max = Math.pow(10.0, cut_ratio);
            }

            imax = -1;
            for (i = i0; i < i1; i++) {
                if (p[i] > max) {
                    max = p[i];
                    imax = i;
                }
            }

            if (imax == -1) // no peak found
                break;

            // get midi note # from imax (FFT freq index)
            if (fp == null) {
                freq = (double) imax / t0;
            } else {
                freq = fp[imax];
                // fprintf (stderr, "freq = %f, %f\n", freq, (double)imax / t0);
            }
            in = MidiFreqRelations.get_note(freq); // midi note #
            // check the range of the note
            if (in >= i0 && in <= i1) {
                // if second time on same note, skip
                if (intens[in] == 0) {
                    /*
                     * scale intensity (velocity) of the peak power range from
                     * 10^cut_ratio to 10^0 is scaled
                     */
                    x = 127.0 / (-cut_ratio)
                            * (Math.log10(p[imax]) - cut_ratio);
                    if (x >= 128.0) {
                        intens[in] = 127;
                    } else if (x > 0) {
                        intens[in] = (byte) x;
                    }
                }
            }

            // subtract peak upto minimum in both sides
            p[imax] = 0.0;
            // right side
            for (i = imax + 1; p[i] != 0.0 && i < (i1 - 1) && p[i] >= p[i + 1]; i++)
                p[i] = 0.0;
            if (i == i1 - 1)
                p[i] = 0.0;
            // left side
            for (i = imax - 1; p[i] != 0.0 && i > i0 && p[i - 1] <= p[i]; i--)
                p[i] = 0.0;
            if (i == i0)
                p[i] = 0.0;
        }
    }

}
