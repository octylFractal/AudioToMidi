package me.kenzierocks.a2m.v2;

import java.io.IOException;
import java.io.OutputStream;

public interface OutStreamSupplier {

    OutputStream get() throws IOException;

}
