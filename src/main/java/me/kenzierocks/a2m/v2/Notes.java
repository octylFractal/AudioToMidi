package me.kenzierocks.a2m.v2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * struct WAON_notes
 */
public class Notes {

    public static final class Note {

        public int step;
        public boolean event;
        public byte note;
        public byte vel;

        public Note(int step, boolean event, byte note, byte vel) {
            this.step = step;
            this.event = event;
            this.note = note;
            this.vel = vel;
        }

    }

    private final List<Note> notes = new ArrayList<>();
    
    public int count() {
        return notes.size();
    }
    
    public Note getNote(int index) {
        return notes.get(index);
    }

    public void append(int step, boolean event, byte note, byte vel) {
        notes.add(new Note(step, event, note, vel));
    }

    public void insert(int index, int step, boolean event, byte note, byte vel) {
        notes.add(index, new Note(step, event, note, vel));
    }

    public void remove_at(int index) {
        notes.remove(index);
    }

    public void regulate() {
        int[] on_step = new int[128];
        int[] on_index = new int[128];

        Arrays.fill(on_step, -1);
        Arrays.fill(on_index, -1);

        for (int i = 0; i < notes.size(); i++) {
            Note n = notes.get(i);
            byte note = n.note;
            if (!n.event) {
                if (on_step[note] < 0 || on_index[note] < 0) {
                    remove_at(i);
                    i--;
                }
                on_step[note] = -1;
                on_index[note] = -1;
            } else {
                if (on_step[note] >= 0 && on_index[note] >= 0) {
                    insert(i, n.step, false, note, (byte) 64);
                }
                on_step[note] = n.step;
                on_index[note] = i;
            }
        }

        int last_step = notes.get(notes.size() - 1).step;
        for (int i = 0; i < 128; i++) {
            if (on_step[i] < 0) {
                continue;
            }
            append(last_step + 1, false, (byte) i, (byte) 64);
        }
    }

    private static void
            check_on_index_for_remove(int[] on_index, int i_rm) {
        for (int i = 0; i < 128; i++) {
            if (on_index[i] > i_rm) {
                on_index[i]--;
            }
        }
    }

    public void remove_shortnotes(int min_duration, int min_vel) {
        int[] on_step = new int[128];
        int[] on_index = new int[128];

        Arrays.fill(on_step, -1);
        Arrays.fill(on_index, -1);

        for (int index = 0; index < notes.size(); index++) {
            Note n = notes.get(index);
            int note = n.note;

            if (!n.event) {
                // off event
                if (on_step[note] < 0 || on_index[note] < 0) {
                    // no on event on the note
                    // so remove this orphant off event
                    remove_at(index);
                    index--;
                } else {
                    int vel = notes.get(on_index[note]).vel;
                    int duration = notes.get(index).step - on_step[note];
                    if (duration <= min_duration && vel <= min_vel) {
                        // remove these on and off events on the note
                        remove_at(index);
                        index--;

                        int index_on = on_index[note];
                        remove_at(index_on);
                        index--;

                        // need to shift indices on on_index[]
                        check_on_index_for_remove(on_index, index_on);
                    }
                }

                // reset on_step[] and on_index[]
                on_step[note] = -1;
                on_index[note] = -1;
            } else {
                // on event
                if (on_step[note] >= 0 && on_index[note] >= 0) {
                    // the note is already on
                    // so, insert off event here
                    insert(index,
                            n.step,
                            false, // off
                            (byte) note,
                            (byte) 64); // default
                    index++;
                }

                // set on_step[] and on_index[]
                on_step[note] = n.step;
                on_index[note] = index;
            }
        }
    }

    public void remove_longnotes(int max_duration, int min_vel) {
        int[] on_step = new int[128];
        int[] on_index = new int[128];

        Arrays.fill(on_step, -1);
        Arrays.fill(on_index, -1);

        for (int index = 0; index < notes.size(); index++) {
            Note n = notes.get(index);
            int note = n.note;

            if (!n.event) {
                // off event
                if (on_step[note] < 0 || on_index[note] < 0) {
                    // no on event on the note
                    // so remove this orphant off event
                    remove_at(index);
                    index--;
                } else {
                    int vel = notes.get(on_index[note]).vel;
                    int duration = notes.get(index).step - on_step[note];
                    if (duration >= max_duration && vel <= min_vel) {
                        // remove these on and off events on the note
                        remove_at(index);
                        index--;

                        int index_on = on_index[note];
                        remove_at(index_on);
                        index--;

                        // need to shift indices on on_index[]
                        check_on_index_for_remove(on_index, index_on);
                    }
                }

                // reset on_step[] and on_index[]
                on_step[note] = -1;
                on_index[note] = -1;
            } else {
                // on event
                if (on_step[note] >= 0 && on_index[note] >= 0) {
                    // the note is already on
                    // so, insert off event here
                    insert(index,
                            n.step,
                            false, // off
                            (byte) note,
                            (byte) 64); // default
                    index++;
                }

                // set on_step[] and on_index[]
                on_step[note] = n.step;
                on_index[note] = index;
            }
        }
    }

    public void remove_octaves() {
        int[] on_step = new int[128];
        int[] on_index = new int[128];
        BitSet flag_remove = new BitSet(128);

        Arrays.fill(on_step, -1);
        Arrays.fill(on_index, -1);

        for (int index = 0; index < notes.size(); index++) {
            Note n = notes.get(index);
            int note = n.note;

            if (!n.event) {
                // off event
                if (on_step[note] < 0 || on_index[note] < 0) {
                    // no on event on the note
                    // so remove this orphant off event
                    remove_at(index);
                    index--;
                } else {
                    if (flag_remove.get(index)) {
                        // remove these on and off events on the note
                        remove_at(index);
                        index--;

                        int index_on = on_index[note];
                        remove_at(index_on);
                        index--;

                        // need to shift indices on on_index[]
                        check_on_index_for_remove(on_index, index_on);
                    }
                }

                // reset on_step[] and on_index[]
                on_step[note] = -1;
                on_index[note] = -1;
            } else {
                // on event
                if (on_step[note] >= 0 && on_index[note] >= 0) {
                    // the note is already on
                    // so, insert off event here
                    insert(index,
                            n.step,
                            false, // off
                            (byte) note,
                            (byte) 64); // default
                    index++;
                }

                // set on_step[] and on_index[]
                on_step[note] = n.step;
                on_index[note] = index;

                flag_remove.clear(note);
                int note_down = note - 12;
                if (note_down < 0) {
                    continue;
                }
                if (on_step[note_down] >= 0 && on_index[note_down] >= 0) {
                    if (n.vel < notes.get(on_index[note_down]).vel) {
                        flag_remove.set(note);
                    }
                }
            }
        }
    }

    public void check(int step, byte[] vel, int[] on_event, int on_threshold, int off_threshold, int peak_threshold) {
        /* loop for notes */
        int i;
        for (i = 0; i < 128; i++) {
            if (on_event[i] < 0) /* off at last step */
            {
                /* check the note-on event by on_threshold */
                if (vel[i] > on_threshold) {
                    /* on */
                    append(
                            step,
                            true, /* on */
                            (byte) i, // midi note
                            vel[i]);
                    on_event[i] = notes.size() - 1; // event index of notes.
                }
            } else /* on at last step */
            {
                /* check the note-off event by off_threshold */
                if (vel[i] <= off_threshold) {
                    /* off */
                    append(step,
                            false, /* off */
                            (byte) i, // midi note
                            (byte) 64);
                    on_event[i] = -1;
                } else /* now note is over off_threshold at least */
                {
                    Note n = notes.get(on_event[i]);
                    if (vel[i] >= (n.vel + peak_threshold)) {
                        /* off */
                        append(step,
                                false, /* off */
                                (byte) i, // midi note
                                (byte) 64);
                        /* on */
                        append(step,
                                true, /* on */
                                (byte) i, // midi note
                                (byte) vel[i]);
                        on_event[i] = notes.size() - 1; // event index of notes.
                    } else if (vel[i] > n.vel) {
                        /* overwrite velocity */
                        n.vel = vel[i];
                    }
                }
            }
        }
    }

}
