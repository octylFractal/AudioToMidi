package me.kenzierocks.a2m.v2;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

public class FfmpegReader {

    private static final ExecutorService COPY_THREADS = Executors.newCachedThreadPool(
            new ThreadFactoryBuilder()
                    .setNameFormat("copy-thread-%d")
                    .setDaemon(true)
                    .build());

    public static InputStream read(InputStream stream, boolean normalizeVolume) throws IOException {
        System.err.println("Opening ffmpeg for reading...");
        ImmutableList.Builder<String> command = ImmutableList.builder();
        command.add("ffmpeg", "-i", "pipe:0");
        if (normalizeVolume) {
            command.add("-filter:a", "dynaudnorm");
        }
        command.add("-f", "wav", "pipe:1");
        System.err.println("FFmpeg command: `" + String.join(" ", command.build()) + "`");
        Process ffmpeg = new ProcessBuilder(command.build()).start();

        COPY_THREADS.submit(() -> {
            ByteStreams.copy(ffmpeg.getErrorStream(), new PrintStream(System.err, true));
            return null;
        });
        COPY_THREADS.submit(() -> {
            try (OutputStream dest = ffmpeg.getOutputStream()) {
                ByteStreams.copy(stream, dest);
            }
            return null;
        });
        System.err.println("Streaming from ffmpeg.");
        return new BufferedInputStream(ffmpeg.getInputStream(), 128 * 1024);
    }

}
