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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.util.PathConverter;
import joptsimple.util.PathProperties;

public class Main {

    private static final Path STDIN = Paths.get("-");
    private static final Path OUTPUT_MID = Paths.get("[file-name].mid");

    private static final OptionParser PARSER = new OptionParser();

    private static final ArgumentAcceptingOptionSpec<Path> INPUT = PARSER.acceptsAll(Arrays.asList("i", "input"), "Input file")
            .withRequiredArg()
            .withValuesConvertedBy(new PathConverter(PathProperties.READABLE))
            .defaultsTo(STDIN);

    private static final ArgumentAcceptingOptionSpec<Path> OUTPUT = PARSER.acceptsAll(Arrays.asList("o", "output"), "Output file")
            .withRequiredArg()
            .withValuesConvertedBy(new PathConverter())
            .defaultsTo(OUTPUT_MID);

    private static final OptionSpec<Void> FFMPEG_READ = PARSER.acceptsAll(Arrays.asList("f", "ffmpeg"), "Use FFmpeg for reading.");
    private static final OptionSpec<Void> NORMALIZE_VOLUME = PARSER.acceptsAll(Arrays.asList("n", "normalize"), "Turn on volume normalization");
    private static final OptionSpec<Void> PV_CORRECTION = PARSER.acceptsAll(Arrays.asList("pv-correction"), "Turn on phase-vocoder correction (from WaoN)");

    private static final OptionSpec<Void> HELP = PARSER.acceptsAll(Arrays.asList("h", "help"), "Print this help.")
            .forHelp();

    public static void main(String[] args) throws Exception {
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

        if (opts.has(HELP) || args.length == 0) {
            PARSER.printHelpOn(System.err);
            return;
        }

        Path input = opts.valueOf(INPUT);
        Path output = getOutputFile(opts, input);
        System.err.println("Converting from `" + input + "` to `" + output + "`...");
        try (InputStream stream = getStream(input, opts)) {
            new Processor(stream, 
                    () -> Files.newOutputStream(output),
                    opts.has(PV_CORRECTION)).process();
        }
    }

    private static Path getOutputFile(OptionSet opts, Path input) {
        Path output = opts.valueOf(OUTPUT);
        if (output == OUTPUT_MID) {
            String fileName = input.getFileName().toString();
            int dotIndex = fileName.lastIndexOf('.');
            String midName = dotIndex < 0
                    ? fileName + ".mid"
                    : fileName.substring(0, dotIndex) + ".mid";
            output = input.resolveSibling(midName);
        }
        return output;
    }

    private static InputStream getStream(Path path, OptionSet opts) throws IOException {
        InputStream stream = getRawInput(path);
        boolean normVol = opts.has(NORMALIZE_VOLUME);
        boolean useFfmpeg = opts.has(FFMPEG_READ) || normVol;
        if (!useFfmpeg) {
            return stream;
        }
        return FfmpegReader.read(stream, normVol);
    }

    private static InputStream getRawInput(Path path) throws IOException {
        if (path == STDIN) {
            return System.in;
        }
        return Files.newInputStream(path);
    }
}
