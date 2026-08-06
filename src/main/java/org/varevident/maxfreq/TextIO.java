package org.varevident.maxfreq;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Buffered plain-text and gzip/BGZF text IO helpers.
 *
 * @author Milad EIDI
 */
final class TextIO {
    private static final int BUFFER_SIZE = 1 << 20;

    private TextIO() {
    }

    static BufferedReader reader(Path path) throws IOException {
        InputStream input = new BufferedInputStream(Files.newInputStream(path), BUFFER_SIZE);
        if (isGzip(path)) {
            input = new GZIPInputStream(input, BUFFER_SIZE);
        }
        return new BufferedReader(new java.io.InputStreamReader(input, StandardCharsets.UTF_8), BUFFER_SIZE);
    }

    static BufferedWriter writer(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        OutputStream output = new BufferedOutputStream(Files.newOutputStream(path), BUFFER_SIZE);
        if (isGzip(path)) {
            output = new GZIPOutputStream(output, BUFFER_SIZE);
        }
        return new BufferedWriter(new java.io.OutputStreamWriter(output, StandardCharsets.UTF_8), BUFFER_SIZE);
    }

    private static boolean isGzip(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".gz") || name.endsWith(".bgz") || name.endsWith(".bgzip");
    }
}
