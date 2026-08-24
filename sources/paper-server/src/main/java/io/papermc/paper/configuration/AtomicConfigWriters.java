package io.papermc.paper.configuration;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.NullMarked;

/**
 * Atomic file writer for configuration files.
 * <p>
 * Mirrors configurate's {@code AtomicFiles}, but preserving the existing file's attributes is best-effort:
 * some filesystems (e.g. sandboxed or browser-hosted JVMs) cannot copy ownership/permission bits and fail
 * the copy with {@code COPY_ATTRIBUTES}; in that case the content is still written atomically via a plain copy.
 */
@NullMarked
public final class AtomicConfigWriters {

    private AtomicConfigWriters() {
    }

    public static Callable<BufferedWriter> atomicWriterFactory(final Path path, final Charset charset) {
        return () -> atomicBufferedWriter(path, charset);
    }

    public static BufferedWriter atomicBufferedWriter(final Path target, final Charset charset) throws IOException {
        final Path path = target.toAbsolutePath();
        final Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        final Path tmp = parent.resolve("." + path.getFileName() + "." + Long.toUnsignedString(ThreadLocalRandom.current().nextLong(), 36) + ".tmp");
        if (Files.exists(path)) {
            io.papermc.paper.util.FileCopies.copy(path, tmp, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
        }
        final BufferedWriter out = Files.newBufferedWriter(tmp, charset);
        return new BufferedWriter(new java.io.Writer() {
            private boolean closed;

            @Override
            public void write(final char[] cbuf, final int off, final int len) throws IOException {
                out.write(cbuf, off, len);
            }

            @Override
            public void flush() throws IOException {
                out.flush();
            }

            @Override
            public void close() throws IOException {
                if (this.closed) return;
                this.closed = true;
                out.close();
                try {
                    Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (final AtomicMoveNotSupportedException e) {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        });
    }
}
