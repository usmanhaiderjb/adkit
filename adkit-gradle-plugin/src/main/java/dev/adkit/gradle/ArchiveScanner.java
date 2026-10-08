package dev.adkit.gradle;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Searches every entry of an APK/AAB (zip) for patterns, as ASCII and as UTF-16LE. */
final class ArchiveScanner {
    private ArchiveScanner() {}

    static final class Finding {
        final String entry;
        final String pattern;

        Finding(String entry, String pattern) {
            this.entry = entry;
            this.pattern = pattern;
        }
    }

    static List<Finding> scan(File archive, List<String> patterns) throws IOException {
        List<byte[][]> needles = new ArrayList<>();
        for (String p : patterns) {
            needles.add(new byte[][]{p.getBytes(StandardCharsets.US_ASCII), p.getBytes(StandardCharsets.UTF_16LE)});
        }
        List<Finding> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                // Native libraries and images never carry ad unit ids.
                if (e.isDirectory() || e.getName().endsWith(".so") || e.getName().endsWith(".png") || e.getName().endsWith(".webp")) continue;
                byte[] data;
                try (InputStream in = zip.getInputStream(e)) {
                    data = readAll(in);
                }
                for (int i = 0; i < patterns.size(); i++) {
                    byte[][] n = needles.get(i);
                    if (indexOf(data, n[0]) >= 0 || indexOf(data, n[1]) >= 0) {
                        out.add(new Finding(e.getName(), patterns.get(i)));
                    }
                }
            }
        }
        return out;
    }

    static int indexOf(byte[] data, byte[] needle) {
        if (needle.length == 0 || data.length < needle.length) return -1;
        byte first = needle[0];
        int max = data.length - needle.length;
        outer:
        for (int i = 0; i <= max; i++) {
            if (data[i] != first) continue;
            for (int j = 1; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[64 * 1024];
        int n;
        while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }
}
