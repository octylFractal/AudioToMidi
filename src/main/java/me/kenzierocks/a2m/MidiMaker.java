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

import static com.google.common.base.Preconditions.checkArgument;
import static org.bytedeco.javacpp.fftw3.FFTW_R2HC;
import static org.bytedeco.javacpp.fftw3.fftw_alloc_real;
import static org.bytedeco.javacpp.fftw3.fftw_execute;
import static org.bytedeco.javacpp.fftw3.fftw_plan_r2r_1d;

import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.DoubleBuffer;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import org.bytedeco.javacpp.fftw3.fftw_plan;

import com.flowpowered.math.GenericMath;
import com.flowpowered.math.TrigMath;

/**
 * The magical MIDI maker!
 * 
 * With help from
 * https://web.archive.org/web/20161204211101/http://blog.bjornroche.com/2012/07/frequency-detection-using-fft-aka-pitch.html
 * and WaoN.
 */
public class MidiMaker {

    private static final float CENTER_FREQ = 440;

    private static final int SET_TEMPO = 0x51;

    public static final int LEN = 2048;

    private static final HannWindow window = new HannWindow(LEN);
    private static final double DENSITY = initDensity();

    private static final int MIN_VEL = 8;
    private static final int MAX_VEL = 128;
    private static final int MIN_NOTE_LENGTH = 2;

    private static final double cutRatio = -4.5;
    private static final double relativeCutRatio = 1;

    // tempo is 120bpm == 500,000mpq == 0x07_a1_20
    private static final byte[] TEMPO = {
            0x07, (byte) 0xA1, 0x20
    };

    public static MidiMaker makeMidi(Sequence midiSeq, float sampleRate) {
        return new MidiMaker(midiSeq, sampleRate);
    }

    private static double initDensity() {
        double den = 0;
        for (int i = 0; i < LEN; i++) {
            double han = window.applyWindow(i, 1);
            den += han * han;
        }
        den *= LEN;
        return den;
    }

    private final DoubleBuffer in = fftw_alloc_real(LEN).limit(LEN).asByteBuffer().asDoubleBuffer();
    private final DoubleBuffer out = fftw_alloc_real(LEN).limit(LEN).asByteBuffer().asDoubleBuffer();

    private final Sequence sequence;
    private final float sampleRate;

    private double secondOrderLowPassA1;
    private double secondOrderLowPassA2;
    private double secondOrderLowPassB1;
    private double secondOrderLowPassB2;
    private double secondOrderLowPassB3;

    private final SOLP solp1;
    private final SOLP solp2;

    private final fftw_plan fftPlan = fftw_plan_r2r_1d(LEN, in, out, FFTW_R2HC, 0);

    private final double timePeriod;
    private final int loRange;
    private final int hiRange;

    public MidiMaker(Sequence midiSeq, float sampleRate) {
        this.sequence = midiSeq;
        this.sampleRate = sampleRate;
        this.timePeriod = LEN / sampleRate;

        loRange = Math.max((int) (MidiFreqRelations.mid2freq[0] * timePeriod - 0.5), 1);
        hiRange = Math.min((int) (MidiFreqRelations.mid2freq[127] * timePeriod - 0.5) + 1, LEN / 2 + 1);

        computeSecondOrderLowPass();

        solp1 = new SOLP();
        solp2 = new SOLP();

        try {
            setTimingData();
        } catch (InvalidMidiDataException e) {
            throw new RuntimeException(e);
        }
    }

    private void setTimingData() throws InvalidMidiDataException {
        sequence.getTracks()[0].add(new MidiEvent(new MetaMessage(SET_TEMPO, TEMPO, 3), 0));
    }

    private void computeSecondOrderLowPass() {
        double a0;
        double w0 = 2 * Math.PI * CENTER_FREQ / sampleRate;
        double cosw0 = TrigMath.cos(w0);
        double sinw0 = TrigMath.sin(w0);
        // float alpha = sinw0/2;
        double alpha = sinw0 / 2 * GenericMath.sqrt(2);

        a0 = 1 + alpha;
        secondOrderLowPassA1 = (-2 * cosw0) / a0;
        secondOrderLowPassA2 = (1 - alpha) / a0;
        secondOrderLowPassB1 = ((1 - cosw0) / 2) / a0;
        secondOrderLowPassB2 = (1 - cosw0) / a0;
        secondOrderLowPassB3 = secondOrderLowPassB1;
    }

    private final class SOLP {

        private final double[] mem = new double[4];

        double processSecondOrderFilter(double x) {
            double ret = secondOrderLowPassB1 * x + secondOrderLowPassB2 * mem[0] + secondOrderLowPassB3 * mem[1]
                    - secondOrderLowPassA1 * mem[2] - secondOrderLowPassA2 * mem[3];

            mem[1] = mem[0];
            mem[0] = x;
            mem[3] = mem[2];
            mem[2] = ret;

            return ret;
        }
    }

    private static final class HannWindow {

        private final double[] window;

        public HannWindow(int length) {
            window = new double[length];
            for (int i = 0; i < length; i++) {
                window[i] = .5 * (1 - TrigMath.cos(2 * Math.PI * i / (length - 1.0)));
            }
        }

        public double applyWindow(int index, double data) {
            return window[index] * data;
        }

    }

    private double[] outFrame = new double[LEN];
    private double[] amp2 = new double[LEN / 2 + 1];
    private int[] velocities = new int[128];
    private int[] noteStarts = new int[128];
    private int[] lastVels = new int[128];
    private int cnt = 0;

    private PrintStream csvWrite;
    {
        try {
            csvWrite = new PrintStream(new BufferedOutputStream(new FileOutputStream("freq.csv")), true);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public void addFrame(int trackMillis, short[] left, short[] right) {
        cnt++;
        checkArgument(left.length == LEN, "incorrect length, given %s, expected %s", left.length, LEN);
        Track trackTheOnlyth = sequence.getTracks()[0];

        injectFrame(left, right);

        fftw_execute(fftPlan);

        int start = this.out.position();
        this.out.get(outFrame);
        this.out.position(start);

        HC_to_amp2(outFrame, DENSITY, amp2);

        writeSpectrogram(amp2);

        get_note_velocities(amp2, null, cutRatio, relativeCutRatio, loRange, hiRange, timePeriod, velocities);

        for (int i = 0; i < velocities.length; i++) {
            int vel = velocities[i];
            int last = lastVels[i];
            boolean velInRange = vel >= MIN_VEL && vel <= MAX_VEL;
            if (last == 0 && velInRange) {
                // turn on note!
                lastVels[i] = vel;
                noteStarts[i] = trackMillis;
            } else if (last != 0) {
                if (velInRange) {
                    lastVels[i] = Math.max(last, vel);
                } else {
                    // turn off note!
                    lastVels[i] = 0;
                    int noteStart = millisToTick(noteStarts[i]);
                    int noteEnd = millisToTick(trackMillis);
                    if ((noteEnd - noteStart) < MIN_NOTE_LENGTH) {
                        continue;
                    }
                    trackTheOnlyth.add(new MidiEvent(noteOn(i, last), noteStart));
                    trackTheOnlyth.add(new MidiEvent(noteOff(i), noteEnd));
                }
            }
        }
    }

    private void writeSpectrogram(double[] amp2) {
        double[] dbVals = new double[amp2.length];
        for (int i = 0; i < amp2.length; i++) {
            dbVals[i] = amp2[i] / timePeriod;// Math.log10(amp2[i]) * 10;
        }
        // csvWrite.println(DoubleStream.of(dbVals)
        // .mapToObj(d -> String.format("%.10f", d))
        // .collect(Collectors.joining(" ")));
    }

    private MidiMessage noteOn(int note, int vel) {
        try {
            return new ShortMessage(ShortMessage.NOTE_ON, 0, note, vel);
        } catch (InvalidMidiDataException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private MidiMessage noteOff(int note) {
        try {
            return new ShortMessage(ShortMessage.NOTE_OFF, 0, note, 0);
        } catch (InvalidMidiDataException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private void get_note_velocities(double[] p, double[] fp,
            double cut_ratio, double rel_cut_ratio,
            int i0, int i1, double t0,
            int[] intens) {
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
        av = 1.0;

        for (;;) {
            // search peak
            // set the threshold to the average
            max = Math.pow(10.0, cut_ratio);

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
                        intens[in] = (int) x;
                    }
                }
            }

            // // subtract peak upto minimum in both sides
            p[imax] = 0.0;
            // // right side
            // for (i = imax + 1; p[i] != 0.0 && i < (i1 - 1) && p[i] >= p[i +
            // 1]; i++)
            // p[i] = 0.0;
            // if (i == i1 - 1)
            // p[i] = 0.0;
            // if (imax > 0) {
            // // left side
            // for (i = imax - 1; p[i] != 0.0 && i > i0 && p[i - 1] <= p[i];
            // i--)
            // p[i] = 0.0;
            // if (i == i0)
            // p[i] = 0.0;
            // }

        }
    }

    private void HC_to_amp2(double[] freq, double scale,
            double[] amp2) {
        int i;
        double rl, im;

        amp2[0] = freq[0] * freq[0] / scale;
        for (i = 1; i < (LEN + 1) / 2; i++) {
            rl = freq[i];
            im = freq[LEN - i];
            amp2[i] = (rl * rl + im * im) / scale;
        }
        if (LEN % 2 == 0) {
            amp2[LEN / 2] = freq[LEN / 2] * freq[LEN / 2] / scale;
        }
    }

    private static final double DOUBLE_TO_SHORT = Math.pow(2, 16 - 1);

    private void injectFrame(short[] left, short[] right) {
        int start = in.position();
        for (int i = 0; i < left.length; i++) {
            double l = left[i] / DOUBLE_TO_SHORT;
            double r = right[i] / DOUBLE_TO_SHORT;
            double data = (l + r) / 2;
            // data = solp1.processSecondOrderFilter(data);
            // data = solp2.processSecondOrderFilter(data);
            double win = window.applyWindow(i, data);
            in.put(win);
        }
        in.position(start);
    }

    private int millisToTick(int millis) {
        int tpq = sequence.getResolution();
        int mpq = 500_000;
        // have T/Q, M/Q (M=microseconds)
        // want: T/M
        // (T/Q)/(M/Q) == (TQ/QM) == T/M
        float tpm = tpq / (float) mpq;
        return (int) (tpm * (millis * 1000));
    }

}
