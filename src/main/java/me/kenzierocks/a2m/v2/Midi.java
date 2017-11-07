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

import java.io.IOException;
import java.io.OutputStream;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import me.kenzierocks.a2m.v2.Notes.Note;

public class Midi {

    private static final int SET_TEMPO = 0x51;
    private static final byte[] TEMPO = {
            0x07, (byte) 0xA1, 0x20
    };

    public static void output_midi(Notes notes, long div, OutputStream out) throws InvalidMidiDataException, IOException {
        Sequence seq = new Sequence(Sequence.PPQ, (int) div, 1);
        Track track = seq.getTracks()[0];
        track.add(new MidiEvent(new MetaMessage(SET_TEMPO, TEMPO, 3), 0));

        for (int i = 0; i < notes.count(); i++) {
            Note n = notes.getNote(i);

            if (n.event) {
                track.add(new MidiEvent(noteOn(n.note, n.vel), n.step));
            } else {
                track.add(new MidiEvent(noteOff(n.note), n.step));
            }
        }

        MidiSystem.write(seq, 1, out);
    }

    private static MidiMessage noteOn(int note, int vel) throws InvalidMidiDataException {
        return new ShortMessage(ShortMessage.NOTE_ON, 0, note, vel);
    }

    private static MidiMessage noteOff(int note) throws InvalidMidiDataException {
        return new ShortMessage(ShortMessage.NOTE_OFF, 0, note, 0);
    }

}
