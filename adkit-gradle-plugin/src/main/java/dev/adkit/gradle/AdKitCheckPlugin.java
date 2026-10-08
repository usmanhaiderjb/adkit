package dev.adkit.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * {@code plugins { id("dev.adkit.check") }} in an app module.
 *
 * Every {@code assemble*Release} / {@code bundle*Release} is followed by {@code adkitCheckTestIds},
 * which scans the produced APK/AAB and fails the build if a Google test ad id is still inside.
 */
public class AdKitCheckPlugin implements Plugin<Project> {
    private static final Pattern RELEASE_TASK = Pattern.compile("^(assemble|bundle)\\w*Release$");

    @Override
    public void apply(Project project) {
        AdKitCheckExtension ext = project.getExtensions().create("adkitCheck", AdKitCheckExtension.class);
        ext.getFailOnTestIds().convention(true);
        ext.getPatterns().convention(Arrays.asList(
                // Built from parts so this plugin's own jar never matches a scan.
                String.join("-", "ca", "app", "pub", "3940256099942544"),
                "/6499/example/"
        ));

        TaskProvider<TestIdScanTask> scan = project.getTasks().register("adkitCheckTestIds", TestIdScanTask.class, t -> {
            t.setGroup("verification");
            t.setDescription("Fails if release APK/AAB outputs contain Google test AdMob ids.");
            t.getArtifacts().from(project.fileTree(project.getLayout().getBuildDirectory().dir("outputs"), tree -> {
                tree.include("apk/**/*release*.apk", "apk/**/*Release*.apk", "bundle/**/*.aab");
                tree.exclude("**/*debug*", "**/*Debug*");
            }));
            t.getPatterns().set(ext.getPatterns());
            t.getFailOnTestIds().set(ext.getFailOnTestIds());
        });

        project.getTasks().configureEach(task -> {
            // finalizedBy already orders the scan after the packaging task.
            if (RELEASE_TASK.matcher(task.getName()).matches()) task.finalizedBy(scan);
        });
    }
}
