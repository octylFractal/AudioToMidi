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
        // apply window in java land to avoid expensive calls
        double[] cache = WindowHelper.getWindowingArray(len);
        data.get(cache);
        for (int i = 0; i < len; i++) {
            cache[i] = cache[i] * apply(i, len) / scale;
        }
        out.put(cache);
    }

}
