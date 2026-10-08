package dsp.machine;

import dsp.core.RomLoader;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class SpectrumFiles {
    private SpectrumFiles() {}

    public static boolean endsWith(String path, String ext) {
        return path.toLowerCase(Locale.ROOT).endsWith(ext.toLowerCase(Locale.ROOT));
    }

    public static byte[] readAll(String path) {
        try {
            Path file = Path.of(path);
            if (Files.isRegularFile(file)) {
                return Files.readAllBytes(file);
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    public static byte[] tryNamed(String dir, String... names) {
        Path base = Path.of(dir);
        if (Files.isRegularFile(base)) {
            byte[] data = readAll(dir);
            if (data != null) {
                return data;
            }
            base = base.getParent() == null ? Path.of(".") : base.getParent();
        }
        for (String name : names) {
            byte[] data = tryOne(base, name);
            if (data != null) {
                return data;
            }
        }
        if (Files.isDirectory(base)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(base, "*.zip")) {
                for (Path zip : stream) {
                    RomLoader loader = new RomLoader();
                    StringBuilder error = new StringBuilder();
                    if (!loader.open(zip.toString(), error)) {
                        continue;
                    }
                    try {
                        for (String name : names) {
                            byte[] data = loader.tryRead(name);
                            if (data != null && data.length > 0) {
                                return data;
                            }
                        }
                    } finally {
                        loader.close();
                    }
                }
            } catch (IOException ignored) {
                // fall through
            }
        }
        RomLoader loader = new RomLoader();
        StringBuilder error = new StringBuilder();
        if (loader.open(dir, error)) {
            try {
                for (String name : names) {
                    byte[] data = loader.tryRead(name);
                    if (data != null && data.length > 0) {
                        return data;
                    }
                }
            } finally {
                loader.close();
            }
        }
        return null;
    }

    private static byte[] tryOne(Path dir, String name) {
        Path direct = dir.resolve(name);
        if (Files.isRegularFile(direct)) {
            try {
                return Files.readAllBytes(direct);
            } catch (IOException e) {
                return null;
            }
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            String wanted = name.toLowerCase(Locale.ROOT);
            for (Path item : stream) {
                if (Files.isRegularFile(item)
                        && item.getFileName().toString().toLowerCase(Locale.ROOT).equals(wanted)) {
                    return Files.readAllBytes(item);
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    public static void copyPage(byte[] dest, byte[] src, int srcOff) {
        int n = Math.min(dest.length, src.length - Math.min(srcOff, src.length));
        if (n > 0) {
            System.arraycopy(src, srcOff, dest, 0, n);
        }
    }

    public static void fail(StringBuilder error, String message) {
        if (error != null) {
            error.setLength(0);
            error.append(message);
        }
    }
}
