package dev.adkit.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@DisableCachingByDefault(because = "Verification only; cheap to rerun")
public abstract class TestIdScanTask extends DefaultTask {

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getArtifacts();

    @Input
    public abstract ListProperty<String> getPatterns();

    @Input
    public abstract Property<Boolean> getFailOnTestIds();

    @TaskAction
    public void scan() throws IOException {
        List<String> findings = new ArrayList<>();
        int scanned = 0;
        for (File file : getArtifacts().getFiles()) {
            if (!file.isFile()) continue;
            scanned++;
            for (ArchiveScanner.Finding f : ArchiveScanner.scan(file, getPatterns().get())) {
                findings.add(file.getName() + " -> " + f.entry + " contains \"" + f.pattern + "\"");
            }
        }
        if (scanned == 0) {
            getLogger().lifecycle("AdKit: no release APK/AAB found to scan.");
            return;
        }
        if (findings.isEmpty()) {
            getLogger().lifecycle("AdKit: scanned " + scanned + " release artifact(s), no test ad ids.");
            return;
        }
        StringBuilder msg = new StringBuilder("AdKit: Google TEST ad ids found in release output:\n");
        for (String f : findings) msg.append("  ").append(f).append('\n');
        msg.append("Use real unit ids for release (AdKit testMode = BuildConfig.DEBUG swaps them only in debug).");
        if (getFailOnTestIds().get()) throw new GradleException(msg.toString());
        getLogger().warn(msg.toString());
    }
}
