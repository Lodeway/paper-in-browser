package io.papermc.paper.util;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * {@link Files#copy}/{@link Files#move} with best-effort attribute preservation.
 * <p>
 * {@link StandardCopyOption#COPY_ATTRIBUTES} makes the copy fail outright on filesystems that cannot set
 * ownership/permissions (sandboxed or browser-hosted JVMs). The content copy is what matters for world,
 * player and config backups, so retry without the attribute flag when the filesystem rejects it.
 */
@NullMarked
public final class FileCopies {

    /**
     * Once a filesystem has rejected attribute copying, stop asking for it (avoids repeated failed attempts and their
     * noise). Environments that know their filesystem cannot set ownership/permissions can declare it up front with
     * {@code -Dpaper.fileAttributeCopy=false}.
     */
    private static volatile boolean attributeCopyUnsupported = "false".equalsIgnoreCase(System.getProperty("paper.fileAttributeCopy"));

    private FileCopies() {
    }

    public static Path copy(final Path source, final Path target, final CopyOption... options) throws IOException {
        if (attributeCopyUnsupported && hasCopyAttributes(options)) {
            return Files.copy(source, target, withoutCopyAttributes(options));
        }
        try {
            return Files.copy(source, target, options);
        } catch (final IOException e) {
            if (!hasCopyAttributes(options)) throw e;
            attributeCopyUnsupported = true;
            Files.deleteIfExists(target);
            return Files.copy(source, target, withoutCopyAttributes(options));
        }
    }

    public static Path move(final Path source, final Path target, final CopyOption... options) throws IOException {
        if (attributeCopyUnsupported && hasCopyAttributes(options)) {
            return Files.move(source, target, withoutCopyAttributes(options));
        }
        try {
            return Files.move(source, target, options);
        } catch (final IOException e) {
            if (!hasCopyAttributes(options)) throw e;
            attributeCopyUnsupported = true;
            return Files.move(source, target, withoutCopyAttributes(options));
        }
    }

    private static boolean hasCopyAttributes(final CopyOption[] options) {
        for (final CopyOption option : options) {
            if (option == StandardCopyOption.COPY_ATTRIBUTES) return true;
        }
        return false;
    }

    private static CopyOption[] withoutCopyAttributes(final CopyOption[] options) {
        final List<CopyOption> kept = new ArrayList<>(options.length);
        for (final CopyOption option : options) {
            if (option != StandardCopyOption.COPY_ATTRIBUTES) kept.add(option);
        }
        return kept.toArray(new CopyOption[0]);
    }
}
