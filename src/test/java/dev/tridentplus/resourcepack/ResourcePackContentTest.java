package dev.tridentplus.resourcepack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResourcePackContentTest {

    private static final String KEY = "enchantment.tridentplus.fork";

    @Test
    @DisplayName("The generated pack contains exactly the four expected entries")
    void packEntries() throws IOException {
        final Map<String, String> entries = readPack();
        assertEquals(4, entries.size());
        assertTrue(entries.containsKey("pack.mcmeta"));
        assertTrue(entries.containsKey("assets/tridentplus/lang/en_us.json"));
        assertTrue(entries.containsKey("assets/tridentplus/lang/ru_ru.json"));
        assertTrue(entries.containsKey("assets/tridentplus/lang/rpr.json"));
    }

    @Test
    @DisplayName("Pack format matches Minecraft 26.2 (88) and accepts older formats")
    void packFormat() throws IOException {
        final String meta = readPack().get("pack.mcmeta");
        assertNotNull(meta);
        assertTrue(meta.contains("\"pack_format\": 88"));
        assertTrue(meta.contains("\"min_inclusive\": 1"));
    }

    @Test
    @DisplayName("en_us = Fork, ru_ru = Нанизывание, rpr = Многонасажденіе")
    void translations() throws IOException {
        final Map<String, String> entries = readPack();

        final String en = entries.get("assets/tridentplus/lang/en_us.json");
        final String ru = entries.get("assets/tridentplus/lang/ru_ru.json");
        final String rpr = entries.get("assets/tridentplus/lang/rpr.json");

        assertLang(en, "Fork");
        assertLang(ru, "Нанизывание");
        assertLang(rpr, "Многонасажденіе");
        assertFalse(rpr.contains("Многонасаждение"),
                "rpr must use the pre-reform і, not modern и");
    }

    private static void assertLang(final String json, final String expected) {
        assertNotNull(json);
        assertTrue(json.contains(KEY), "missing translation key");
        assertTrue(json.contains("\"" + expected + "\""),
                "missing translation value " + expected);
        assertFalse(json.contains("\uFFFD"), "encoding damage in " + expected);
    }

    private static Map<String, String> readPack() throws IOException {
        final byte[] bytes = ResourcePackBuilder.build(
                ResourcePackContentTest.class.getClassLoader());
        final Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(),
                        new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
