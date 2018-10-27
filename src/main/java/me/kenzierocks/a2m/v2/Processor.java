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

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.DoubleBuffer;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.lwjgl.BufferUtils;

import me.kenzierocks.a2m.MidiFreqRelations;
import me.kenzierocks.a2m.v2.ParallelWindower.TaskResult;

public class Processor {

    private final InputStream stream;
    private final OutputStream out;

    public Processor(InputStream stream, OutputStream out) {
        this.stream = stream.markSupported() ? stream : new BufferedInputStream(stream);
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
        int notetop = 127;// 103; /* G8 */
        int notelow = 0;// 28; /* E2 */

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

        int windowSize = (len / 2) + 1;
        double[] p = new double[windowSize];
        double[] p0 = new double[windowSize];
        double[] dphi = new double[windowSize];
        double[] ph0 = new double[windowSize];
        double[] ph1 = new double[windowSize];

        AudioInputStream __temp = AudioSystem.getAudioInputStream(stream);
        AudioFormat __temp_format = __temp.getFormat();

        AudioFormat sfinfo = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                __temp_format.getSampleRate(),
                16,
                __temp_format.getChannels(),
                __temp_format.getChannels() * 2,
                __temp_format.getSampleRate(),
                true);
        AudioInputStream audioStreamReformatted = AudioSystem.getAudioInputStream(sfinfo, __temp);
        InputStream sf = new BufferedInputStream(audioStreamReformatted);

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

        DoubleSource audioData = chunkStream(sf, sfinfo);
        long size = __temp.getFrameLength();

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

        // size is in frames or samples, same thing to us in mono.
        if (size >= 0) {
            System.err.println("Estimated audio length: " + formatSeconds(size / sampsPerSecond));
        } else {
            System.err.println("Unknown audio length.");
        }

        Extern.pitch_shift = 0.0;
        Extern.n_pitch = 0;
        double seconds = 0;
        double prevSeconds = 0;
        final double lend = len;
        final double hopd = hop;
        final double twopi = 2.0 * Math.PI;
        final double freqCorrect = twopi * hopd;
        double dSampRate = (double) sfinfo.getSampleRate();
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

            double[] ph0Prev = ph0;
            double[] p0Prev = p0;
            ph0 = ph1;
            p0 = p;
            if (icnt == 0) {
                // first step, so no ph0[] yet
            } else {
                // freq correction by phase difference
                for (int i = 0; i < windowSize; ++i) {
                    double dphiI = ph1[i] - ph0Prev[i]
                            - twopi * (double) i / lend * hopd;
                    dphiI = correctPhiMod(dphiI);

                    // frequency correction
                    // NOTE: freq is (i / len + dphi) * samplerate [Hz]
                    dphi[i] = dphiI / freqCorrect;

                    // then, average the power for the analysis
                    p[i] = (FastTrig.sqrt(p[i]) + FastTrig.sqrt(p0Prev[i])) / 2;
                    p[i] = p[i] * p[i];
                }
            }

            // with phase-vocoder correction
            // make corrected frequency (i / len + dphi) * samplerate [Hz]
            for (int i = 0; i < windowSize; ++i) {
                dphi[i] = ((double) i / lend + dphi[i])
                        * dSampRate;
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

        long div = (long) (0.5 * dSampRate / (double) hop);
        Midi.output_midi(notes, div, out);
    }

    private static final double TWO_PI = Math.PI * 2;

    public static double correctPhiLoop(double dphi) {
        for (; dphi >= Math.PI; dphi -= TWO_PI)
            ;
        for (; dphi < -Math.PI; dphi += TWO_PI)
            ;
        return dphi;
    }

    public static double correctPhiMod(double dphiI) {
        double signedPi = Math.copySign(Math.PI, dphiI);
        return (dphiI + signedPi) % TWO_PI - signedPi;
    }

    private DoubleSource chunkStream(InputStream sf, AudioFormat sfinfo) throws IOException {
        DataInputStream stream = new DataInputStream(sf);
        AtomicBoolean complete = new AtomicBoolean(false);
        BlockingQueue<DoubleBuffer> availableBuffers = new LinkedBlockingQueue<>(1024);
        startIoThreads(sfinfo, stream, complete, availableBuffers);
        return new DoubleSource() {

            private DoubleBuffer currentBuffer;

            @Override
            public boolean fillBuffer(DoubleBuffer buffer, int amount) {
                boolean moreData = true;
                int added = 0;
                while (added < amount) {
                    if (currentBuffer == null) {
                        if (availableBuffers.isEmpty() && complete.get()) {
                            moreData = false;
                            break;
                        }
                        try {
                            currentBuffer = availableBuffers.take();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }

                    int dstRemain = amount - added;
                    if (currentBuffer.remaining() < dstRemain) {
                        added += currentBuffer.remaining();
                        buffer.put(currentBuffer);
                        currentBuffer = null;
                    } else {
                        added += dstRemain;
                        DoubleBuffer cp = currentBuffer.slice();
                        cp.limit(dstRemain);
                        buffer.put(cp);
                        currentBuffer.position(currentBuffer.position() + dstRemain);
                    }
                }

                buffer.flip();
                return moreData;
            }

        };
    }

    private void startIoThreads(AudioFormat sfinfo, DataInputStream stream, AtomicBoolean complete, BlockingQueue<DoubleBuffer> availableBuffers) {
        Thread t = new Thread(() -> {
            boolean moreData = true;
            while (moreData) {
                DoubleBuffer buffer = BufferUtils.createDoubleBuffer(64 * 1024);
                while (buffer.hasRemaining()) {
                    try {
                        if (sfinfo.getChannels() == 1) {
                            // just directly read
                            buffer.put(DOUBLE(stream.readShort()));
                        } else {
                            // average l/r
                            double l = DOUBLE(stream.readShort());
                            double r = DOUBLE(stream.readShort());
                            buffer.put((l + r) / 2);
                        }
                    } catch (EOFException end) {
                        moreData = false;
                        break;
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                buffer.flip();
                try {
                    availableBuffers.put(buffer);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            complete.set(true);
        }, "I/O Thread");
        t.start();
    }

    private static final double DTS_FACTOR = Math.pow(2, Short.SIZE - 1);

    static double DOUBLE(short s) {
        return s / DTS_FACTOR;
    }

    private static String formatSeconds(double seconds) {
        int min = (int) (seconds / 60);
        int sec = (int) (seconds - min * 60);
        return String.format("%02d:%02d", min, sec);
    }

}
