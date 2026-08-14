package com.mcworldexplorer.experimental.v05;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class V05ReportWriterTest {
    @Test
    void escapesJsonControlCharacters() {
        assertEquals("a\\\"b\\\\c\\n", V05ReportWriter.escape("a\"b\\c\n"));
    }
}
