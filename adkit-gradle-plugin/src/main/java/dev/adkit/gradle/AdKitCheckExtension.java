package dev.adkit.gradle;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/** {@code adkitCheck { failOnTestIds = false }} to only warn. */
public abstract class AdKitCheckExtension {
    public abstract Property<Boolean> getFailOnTestIds();

    /** Byte patterns searched in every entry (ASCII and UTF-16LE). */
    public abstract ListProperty<String> getPatterns();
}
