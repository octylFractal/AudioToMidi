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

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.swing.JFileChooser;

public class Main {

    public static void main(String[] args) throws Exception {
        String file;
        args = new String[]{"Wonderwall--PubrF0ghE4.mp3"};
        if (args.length > 0) {
            file = args[0];
        } else {
            JFileChooser chooser = new JFileChooser(new File("."));
            int decision = chooser.showOpenDialog(null);
            if (decision != JFileChooser.APPROVE_OPTION) {
                return;
            }
            file = chooser.getSelectedFile().toString();
        }
        AudioInputStream stream = AudioSystem.getAudioInputStream(Paths.get(file).toFile());
        AudioFormat format = stream.getFormat();
        AudioFormat decodedFormat = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                format.getSampleRate(),
                16,
                format.getChannels(),
                format.getChannels() * 2,
                format.getSampleRate(),
                true);
        AudioInputStream din = AudioSystem.getAudioInputStream(decodedFormat, stream);

        int channels = decodedFormat.getChannels();
        int frameSize = decodedFormat.getFrameSize();
        System.err.println(decodedFormat);

        if (frameSize == -1) {
            frameSize = 2;
        }

        doTheMusicyThing(din, channels, frameSize);
    }

    private static void doTheMusicyThing(AudioInputStream stream, int channels, int frameSize) throws Exception {
        DataInputStream dis = new DataInputStream(new BufferedInputStream(stream));
        short[] left = new short[frameSize / 2 * hop];
        short[] right = new short[frameSize / 2 * hop];
        short[][] together = { left, right };
        int frames = 0;
        boolean reading = true;
        while (reading) {
            int read = 0;
            while (read < left.length * 2) {
                short[] buf = read % 2 == 0 ? left : right;
                try {
                    buf[read / 2] = dis.readShort();
                } catch (EOFException e) {
                    reading = false;
                    break;
                }
                read++;
            }

            frames += read / frameSize;

            // F / (F/S) == (F*S)/F == S
            float seconds = frames / stream.getFormat().getFrameRate();
            process(stream.getFormat().getSampleRate(), (int) (1000 * seconds), together);
        }

        playThatResult(stream.getFormat());
    }

    private static int[][] unleave(int channels, byte[] buffer, int read) {
        int[][] unleavened = new int[channels][read / channels / 2];
        for (int i = 0; i < read; i += channels * 2) {
            for (int c = 0; c < channels; c++) {
                unleavened[c][i / (channels * 2)] = (buffer[i + c * 2] & 0xff)
                        | ((buffer[i + c * 2 + 1] & 0xff) << 8);
            }
        }
        return unleavened;
    }

    // resolution of 96 ticks per quarternote
    private static final Sequence midiSeq;
    static {
        try {
            midiSeq = new Sequence(Sequence.PPQ, 96, 1);
        } catch (InvalidMidiDataException e) {
            throw new RuntimeException(e);
        }
    }

    private static void playThatResult(AudioFormat original) throws Exception {
        MidiSystem.write(midiSeq, 1, new File("latest.mid"));
    }

    private static MidiMaker maker;

    private static final short[] frameL = new short[MidiMaker.LEN];
    private static final short[] frameR = new short[MidiMaker.LEN];
    private static final int hop = frameL.length;
    private static int frameFill = 0;

    private static void process(float sampleRate, int trackMillis, short[][] unleavened) throws IOException {
        if (maker == null) {
            maker = MidiMaker.makeMidi(midiSeq, sampleRate);
        }
        short[] left = unleavened[0];
        short[] right = unleavened.length == 1 ? unleavened[0] : unleavened[1];

        int copyLen = Math.min(frameL.length - frameFill, left.length);
        System.arraycopy(left, 0, frameL, frameFill, copyLen);
        System.arraycopy(right, 0, frameR, frameFill, copyLen);
        frameFill += copyLen;
        if (frameFill >= frameL.length) {
            maker.addFrame(trackMillis, frameL, frameR);
            for (int i = 0; i < frameL.length - hop; i++) {
                frameL[i] = frameL[i + hop];
                frameR[i] = frameR[i + hop];
            }
            frameFill = frameL.length - hop;
        }
    }

    private static short[] monoMix(short[] c1, short[] c2) {
        short[] mono = new short[c1.length];
        for (int i = 0; i < c1.length; i++) {
            mono[i] = (short) ((c1[i] + c2[i]) / 2);
        }
        return mono;
    }

}
