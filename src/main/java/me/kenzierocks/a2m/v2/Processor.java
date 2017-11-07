package me.kenzierocks.a2m.v2;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.DoubleBuffer;
import java.util.Arrays;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.bytedeco.javacpp.fftw3;
import org.bytedeco.javacpp.fftw3.fftw_plan;

import me.kenzierocks.a2m.MidiFreqRelations;

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

        DoubleBuffer in = DoubleBuffer.allocate(len);

        DoubleBuffer x = fftw3.fftw_alloc_real(len).limit(len).asBuffer();
        DoubleBuffer y = fftw3.fftw_alloc_real(len).limit(len).asBuffer();

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
        double den = flag_window.init_den(len);

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

        fftw_plan plan = fftw3.fftw_plan_r2r_1d(len, x, y, fftw3.FFTW_R2HC, (int) fftw3.FFTW_ESTIMATE);

        if (hop != len) {
            sndfile_read(sf, sfinfo, position(in, hop), len - hop);
        }

        Extern.pitch_shift = 0.0;
        Extern.n_pitch = 0;
        for (int icnt = 0;; icnt++) {
            // prepare
            in.position(0);
            // shift
            in.put(position(in, hop));
            // read
            try {
                sndfile_read(sf, sfinfo, in, hop);
            } catch (EOFException e) {
                break;
            }

            in.flip();

            // move into fft input
            x.clear();
            x.put(in);
            x.flip();

            // windowify
            flag_window.windowing(len, position(x, 0), 1.0, x);
            x.flip();

            fftw3.fftw_execute(plan);

            HC.to_polar2(len, y, 0, den, p, ph1);

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

        notes.regulate();
        notes.remove_shortnotes(1, 64);
        notes.remove_shortnotes(2, 28);
        notes.remove_octaves();

        long div = (long) (0.5 * (double) sfinfo.getSampleRate() / (double) hop);
        Midi.output_midi(notes, div, out);

        fftw3.fftw_destroy_plan(plan);
    }

    private DoubleBuffer position(DoubleBuffer buf, int pos) {
        DoubleBuffer copy = buf.duplicate();
        copy.position(pos);
        return copy;
    }

    private short[] cachedBuf;

    private void sndfile_read(InputStream sf, AudioFormat sfinfo, DoubleBuffer in, int len) throws IOException {
        DataInputStream stream = new DataInputStream(sf);
        if (cachedBuf == null || cachedBuf.length < len) {
            cachedBuf = new short[len];
        }
        for (int i = 0; i < len; i++) {
            if (sfinfo.getChannels() == 1) {
                // just directly read
                in.put(DOUBLE(stream.readShort()));
            } else {
                // average l/r
                double l = DOUBLE(stream.readShort());
                double r = DOUBLE(stream.readShort());
                in.put((l + r) / 2);
            }
        }
    }

    private static final double DTS_FACTOR = Math.pow(2, Short.SIZE - 1);

    private static double DOUBLE(short s) {
        return s / DTS_FACTOR;
    }

}
