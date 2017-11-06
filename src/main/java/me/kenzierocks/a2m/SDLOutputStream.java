package me.kenzierocks.a2m;

import java.io.IOException;
import java.io.OutputStream;

import javax.sound.sampled.SourceDataLine;

public class SDLOutputStream extends OutputStream {

    private final SourceDataLine target;

    public SDLOutputStream(SourceDataLine target) {
        this.target = target;
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[] { (byte) b });
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        target.write(b, off, len);
    }

}
