package dev.tridentplus.resourcepack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Pure pack assembly with no Bukkit imports, so it can be unit tested without a
 * server. The zip layout is the standard resource-pack layout.
 */
final class ResourcePackBuilder {

    static final List<String> PACK_FILES = List.of(
            "pack.mcmeta",
            "assets/tridentplus/lang/en_us.json",
            "assets/tridentplus/lang/ru_ru.json",
            "assets/tridentplus/lang/rpr.json"
    );

    private ResourcePackBuilder() {
    }

    static byte[] build(final ClassLoader loader) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (final String name : PACK_FILES) {
                try (InputStream in = loader.getResourceAsStream("resourcepack/" + name)) {
                    if (in == null) {
                        throw new IOException("Missing resourcepack/" + name);
                    }
                    final ZipEntry entry = new ZipEntry(name);
                    // A fixed timestamp keeps the SHA-1 stable across restarts.
                    entry.setTime(0L);
                    zip.putNextEntry(entry);
                    in.transferTo(zip);
                    zip.closeEntry();
                }
            }
        }
        return bytes.toByteArray();
    }
}
