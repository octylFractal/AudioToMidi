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
package me.kenzierocks.a2m;

public class MidiFreqRelations {

    public static final double[] mid2freq =
            {
                    // C-1 -
                    8.175799, 8.661957, 9.177024, 9.722718, 10.300861, 10.913382,
                    11.562326, 12.249857, 12.978272, 13.750000, 14.567618, 15.433853,
                    // C0 -
                    16.351598, 17.323914, 18.354048, 19.445436, 20.601722, 21.826764,
                    23.124651, 24.499715, 25.956544, 27.500000, 29.135235, 30.867706,
                    // C1 -
                    32.703196, 34.647829, 36.708096, 38.890873, 41.203445, 43.653529,
                    46.249303, 48.999429, 51.913087, 55.000000, 58.270470, 61.735413,
                    // C2 -
                    65.406391, 69.295658, 73.416192, 77.781746, 82.406889, 87.307058,
                    92.498606, 97.998859, 103.826174, 110.000000, 116.540940, 123.470825,
                    // C3 -
                    130.812783, 138.591315, 146.832384, 155.563492, 164.813778, 174.614116,
                    184.997211, 195.997718, 207.652349, 220.000000, 233.081881, 246.941651,
                    // C4 -
                    261.625565, 277.182631, 293.664768, 311.126984, 329.627557, 349.228231,
                    369.994423, 391.995436, 415.304698, 440.000000, 466.163762, 493.883301,
                    // C5 -
                    523.251131, 554.365262, 587.329536, 622.253967, 659.255114, 698.456463,
                    739.988845, 783.990872, 830.609395, 880.000000, 932.327523, 987.766603,
                    // C6 -
                    1046.502261, 1108.730524, 1174.659072, 1244.507935,
                    1318.510228, 1396.912926, 1479.977691, 1567.981744,
                    1661.218790, 1760.000000, 1864.655046, 1975.533205,
                    // C7 -
                    2093.004522, 2217.461048, 2349.318143, 2489.015870,
                    2637.020455, 2793.825851, 2959.955382, 3135.963488,
                    3322.437581, 3520.000000, 3729.310092, 3951.066410,
                    // C8 -
                    4186.009045, 4434.922096, 4698.636287, 4978.031740,
                    5274.040911, 5587.651703, 5919.910763, 6271.926976,
                    6644.875161, 7040.000000, 7458.620184, 7902.132820,
                    // C9 - G9
                    8372.018090, 8869.844191, 9397.272573, 9956.063479,
                    10548.081821, 11175.303406, 11839.821527, 12543.853951
            };

    public static int get_note(double freq) {

        double factor = 1.731234049066756242e+01; /* 12/log(2) */
        double dnote;
        int inote;
        /* MIDI note # 69 is A4(440Hz) */
        dnote = 69.5 + factor * Math.log(freq / 440.0);
        inote = (int) dnote;

        return inote;
    }
}
