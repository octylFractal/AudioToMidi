package me.kenzierocks.a2m.v2;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.util.PathConverter;
import joptsimple.util.PathProperties;

public class Main {

    private static final Path STDIN = Paths.get("/STDIN_INVALID_PATH_THAT_NO_ONE_SHOULD_USE/");

    private static final OptionParser PARSER = new OptionParser();

    private static final ArgumentAcceptingOptionSpec<Path> INPUT = PARSER.acceptsAll(Arrays.asList("i", "input"), "Input file")
            .withRequiredArg()
            .withValuesConvertedBy(new PathConverter(PathProperties.READABLE))
            .defaultsTo(STDIN);

    private static final ArgumentAcceptingOptionSpec<Path> OUTPUT = PARSER.acceptsAll(Arrays.asList("o", "output"), "Output file")
            .withRequiredArg()
            .withValuesConvertedBy(new PathConverter())
            .defaultsTo(Paths.get("output.mid"));

    public static void main(String[] args) throws Exception {
        args = new String[] { "-i", "Rick Astley - Never Gonna Give You Up-dQw4w9WgXcQ.mp3" };
        OptionSet opts;
        try {
            opts = PARSER.parse(args);
            opts.valueOf(INPUT);
            opts.valueOf(OUTPUT);
        } catch (OptionException e) {
            System.err.println(e.getLocalizedMessage());
            System.exit(1);
            return;
        }

        try (InputStream stream = getStream(opts.valueOf(INPUT));
                OutputStream out = Files.newOutputStream(opts.valueOf(OUTPUT))) {
            new Processor(stream, out).process();
        }
    }

    private static InputStream getStream(Path path) throws IOException {
        if (path == STDIN) {
            return System.in;
        }
        return Files.newInputStream(path);
    }
}
