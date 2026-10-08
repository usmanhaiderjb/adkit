package dev.adkit.gradle;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;

public class ArchiveScannerTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String PUB = String.join("-", "ca", "app", "pub", "3940256099942544");

    private File zip(String... nameAndContent) throws Exception {
        File f = tmp.newFile("app-release.apk");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(f))) {
            for (int i = 0; i < nameAndContent.length; i += 2) {
                out.putNextEntry(new ZipEntry(nameAndContent[i]));
                byte[] bytes = nameAndContent[i].endsWith(".arsc")
                        ? nameAndContent[i + 1].getBytes(StandardCharsets.UTF_16LE)
                        : nameAndContent[i + 1].getBytes(StandardCharsets.UTF_8);
                out.write(bytes);
                out.closeEntry();
            }
        }
        return f;
    }

    @Test public void findsAsciiInDex() throws Exception {
        File apk = zip("classes.dex", "xx" + PUB + "/1033173712yy", "lib/arm64/libx.so", PUB);
        List<ArchiveScanner.Finding> f = ArchiveScanner.scan(apk, Arrays.asList(PUB));
        assertEquals(1, f.size());
        assertEquals("classes.dex", f.get(0).entry);
    }

    @Test public void findsUtf16InResources() throws Exception {
        File apk = zip("resources.arsc", "id=" + PUB + "~3347511713");
        assertEquals(1, ArchiveScanner.scan(apk, Arrays.asList(PUB)).size());
    }

    @Test public void cleanArchivePasses() throws Exception {
        File apk = zip("classes.dex", "ca-app-pub-1234567890123456/111", "AndroidManifest.xml", "3940256099942544 alone");
        assertEquals(0, ArchiveScanner.scan(apk, Arrays.asList(PUB)).size());
    }

    @Test public void indexOfEdges() {
        byte[] d = "abcabd".getBytes(StandardCharsets.US_ASCII);
        assertEquals(3, ArchiveScanner.indexOf(d, "abd".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(-1, ArchiveScanner.indexOf(d, "abe".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(-1, ArchiveScanner.indexOf(new byte[0], "a".getBytes(StandardCharsets.US_ASCII)));
    }
}