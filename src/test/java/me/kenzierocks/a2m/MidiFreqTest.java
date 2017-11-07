package me.kenzierocks.a2m;

import static org.junit.Assert.*;

import org.junit.Test;

public class MidiFreqTest {

    @Test
    public void testNote() throws Exception {
        assertEquals(69, MidiFreqRelations.get_note(440));
        assertEquals(68, MidiFreqRelations.get_note(415));
    }
}
