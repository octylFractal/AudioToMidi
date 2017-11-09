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

import static com.google.common.base.Preconditions.checkState;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.DoubleBuffer;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.lwjgl.system.MemoryUtil;

import me.kenzierocks.a2m.MidiFreqRelations;
import me.kenzierocks.a2m.v2.ParallelWindower.TaskResult;

public class Processor {

    private final InputStream stream;
    private final OutputStream out;

    public Processor(InputStream stream, OutputStream out) {
        this.stream = stream;
        this.out = out;
    }

    public void process() throws Exception {
        double cut_ratio; // log10 of cutoff ratio for scale velocity
        cut_ratio = -5.0;
        double rel_cut_ratio; // log10 of cutoff ratio relative to average
        rel_cut_ratio = 1.0; // this value is ignored when abs_flg == 1
        int len = 4096;
        Window flag_window = StandardWindows.HANNING;
        /* for 76 keys piano */
        int notetop = 103; /* G8 */
        int notelow = 28; /* E2 */

        Extern.abs_flg = true;

        int hop = len / 8;
        Extern.adj_pitch = 0.0;
        /* to select peaks in a note */
        int peak_threshold = 128; /* this means no peak search */

        // boolean flag_phase = true; // use the phase correction
        // int psub_n = 0;
        // double psub_f = 0.0;
        // double oct_f = 0.0;

        Notes notes = new Notes();
        byte[] vel = new byte[128];
        int[] on_event = new int[128];
        Arrays.fill(on_event, -1);

        double[] p = new double[(len / 2) + 1];
        double[] p0 = new double[(len / 2) + 1];
        double[] dphi = new double[(len / 2) + 1];
        double[] ph0 = new double[(len / 2) + 1];
        double[] ph1 = new double[(len / 2) + 1];

        AudioInputStream __temp = AudioSystem.getAudioInputStream(stream);
        AudioFormat __temp_format = __temp.getFormat();

        AudioFormat sfinfo = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                __temp_format.getSampleRate(),
                16,
                __temp_format.getChannels(),
                __temp_format.getChannels() * 2,
                __temp_format.getSampleRate(),
                true);
        InputStream sf = AudioSystem.getAudioInputStream(sfinfo, __temp);
        sf = new BufferedInputStream(sf);

        System.err.println(sfinfo);

        if (sfinfo.getChannels() != 2 && sfinfo.getChannels() != 1) {
            throw new IllegalStateException("Only stereo and mono inputs are supported.");
        }

        double t0 = ((double) len) / sfinfo.getSampleRate();

        /* set range to analyse (search notes) */
        /* -- after 't0' is calculated */
        int i0 = (int) (MidiFreqRelations.mid2freq[notelow] * t0 - 0.5);
        int i1 = (int) (MidiFreqRelations.mid2freq[notetop] * t0 - 0.5) + 1;
        if (i0 <= 0) {
            i0 = 1; // i0=0 means DC component (frequency = 0)
        }
        if (i1 >= (len / 2)) {
            i1 = len / 2 - 1;
        }

        DoubleBuffer audioData = readAudioData(sf, sfinfo);
        int size = audioData.remaining();

        ExecutorService pool = Executors.newWorkStealingPool();
        Iterator<TaskResult> buffers = new ParallelWindower(flag_window, audioData, len, hop)
                .process(pool);

        // Samples per second (s/e)
        double sampsPerSecond = sfinfo.getSampleRate();
        // Samples per hop (s/h)
        double sampsPerHop = hop;
        // e/h = (s/h)/(s/e)
        double secondsPerHop = sampsPerHop / sampsPerSecond;
        System.err.printf("%,f sec/loop%n", secondsPerHop);

        // size is in samples
        System.err.println("Estimated audio length: " + formatSeconds(size / sampsPerSecond));

        Extern.pitch_shift = 0.0;
        Extern.n_pitch = 0;
        double seconds = 0;
        double prevSeconds = 0;
        for (int icnt = 0; buffers.hasNext(); icnt++) {
            TaskResult res = buffers.next();
            p = res.p().array;
            ph1 = res.ph1().array;

            seconds += secondsPerHop;

            while ((seconds - prevSeconds) >= 10) {
                prevSeconds += 10;
                System.err.println(formatSeconds(prevSeconds));
            }

            // with phase-vocoder correction
            if (icnt == 0) {
                // first step, so no ph0[] yet
                for (int i = 0; i < (len / 2 + 1); ++i) {
                    // no correction
                    dphi[i] = 0.0;

                    // backup the phase for the next step
                    p0[i] = p[i];
                    ph0[i] = ph1[i];
                }
            } else {
                // freq correction by phase difference
                for (int i = 0; i < (len / 2 + 1); ++i) {
                    double twopi = 2.0 * Math.PI;
                    // double dphi;
                    dphi[i] = ph1[i] - ph0[i]
                            - twopi * (double) i / (double) len * (double) hop;
                    for (; dphi[i] >= Math.PI; dphi[i] -= twopi)
                        ;
                    for (; dphi[i] < -Math.PI; dphi[i] += twopi)
                        ;

                    // frequency correction
                    // NOTE: freq is (i / len + dphi) * samplerate [Hz]
                    dphi[i] = dphi[i] / twopi / (double) hop;

                    // backup the phase for the next step
                    p0[i] = p[i];
                    ph0[i] = ph1[i];

                    // then, average the power for the analysis
                    p[i] = 0.5 * (Math.sqrt(p[i]) + Math.sqrt(p0[i]));
                    p[i] = p[i] * p[i];
                }
            }

            // with phase-vocoder correction
            // make corrected frequency (i / len + dphi) * samplerate [Hz]
            for (int i = 0; i < (len / 2 + 1); ++i) {
                dphi[i] = ((double) i / (double) len + dphi[i])
                        * (double) sfinfo.getSampleRate();
            }
            Analyze.note_intensity(p, dphi,
                    cut_ratio, rel_cut_ratio, i0, i1, t0, vel);

            notes.check(icnt, vel, on_event, 8, 0, peak_threshold);
        }
        pool.shutdown();

        System.err.println();

        notes.regulate();
        notes.remove_shortnotes(1, 64);
        notes.remove_shortnotes(2, 28);
        notes.remove_octaves();

        long div = (long) (0.5 * (double) sfinfo.getSampleRate() / (double) hop);
        Midi.output_midi(notes, div, out);
    }

    private static final int DEFAULT_EXPECTED_SIZE = 6 * 1024 * 1024;

    private DoubleBuffer readAudioData(InputStream sf, AudioFormat sfinfo) throws IOException {
        System.err.println("Reading into data...");
        DoubleBuffer audioData = MemoryUtil.memAllocDouble(
                Math.max(sf.available() / Short.SIZE, DEFAULT_EXPECTED_SIZE));
        DataInputStream stream = new DataInputStream(sf);
        while (true) {
            try {
                if (!audioData.hasRemaining()) {
                    int startSize = audioData.capacity();
                    int expandSize = expandFactor(startSize);
                    System.err.print("Re-alloc from " + startSize + " to " + expandSize + "...");
                    audioData = MemoryUtil.memRealloc(audioData, expandSize);
                    System.err.println("done!");
                    checkState(audioData != null, "failed to realloc for audio: original %s, expanded %s",
                            startSize, expandSize);
                }
                if (sfinfo.getChannels() == 1) {
                    // just directly read
                    audioData.put(DOUBLE(stream.readShort()));
                } else {
                    // average l/r
                    double l = DOUBLE(stream.readShort());
                    double r = DOUBLE(stream.readShort());
                    audioData.put((l + r) / 2);
                }
            } catch (EOFException end) {
                break;
            }
        }
        audioData.flip();
        int startSize = audioData.capacity();
        int endSize = audioData.remaining();
        audioData = MemoryUtil.memRealloc(audioData, endSize);
        checkState(audioData != null, "failed to realloc for audio: original %s, expanded %s",
                startSize, endSize);
        System.err.println(audioData);
        return audioData;
    }

    private static int expandFactor(int capacity) {
        return capacity + (capacity >> 1);
    }

    private static final double DTS_FACTOR = Math.pow(2, Short.SIZE - 1);

    private static double DOUBLE(short s) {
        return s / DTS_FACTOR;
    }

    private static String formatSeconds(double seconds) {
        int min = (int) (seconds / 60);
        int sec = (int) (seconds - min * 60);
        return String.format("%02d:%02d", min, sec);
    }

}
