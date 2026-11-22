package com.edupulse.ragcommon.chunking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextCleanerTest {

    // ---- failure / edge paths ----

    @Test
    void nullInputBecomesEmptyString() {
        assertEquals("", TextCleaner.clean(null));
    }

    @Test
    void controlCharactersAreRemoved() {
        assertEquals("abc", TextCleaner.clean("a\u0000b\u0007c"));
    }

    @Test
    void lineEndingHyphenBeforeUppercaseIsNotJoined() {
        assertEquals("Section One-\nNext heading", TextCleaner.clean("Section One-\nNext heading"));
    }

    // ---- success paths ----

    @Test
    void romanianCedillaLettersBecomeCommaBelow() {
        assertEquals("și țară Ș Ț", TextCleaner.clean("şi ţară Ş Ţ"));
    }

    @Test
    void ligaturesSoftHyphensAndNonBreakingSpacesAreNormalized() {
        assertEquals("final flow office", TextCleaner.clean("ﬁnal ﬂow oﬃce"));
        assertEquals("cooperare", TextCleaner.clean("co­operare"));
        assertEquals("100 lei", TextCleaner.clean("100 lei"));
    }

    @Test
    void lineBreakHyphenIsDroppedButRealHyphensAreKept() {
        assertEquals("example", TextCleaner.clean("exam-\nple"));
        assertEquals("e-money", TextCleaner.clean("e-\nmoney"));
        assertEquals("https://exam-ple.com", TextCleaner.clean("https://exam-\nple.com"));
    }

    @Test
    void whitespaceAndBlankLinesAreCollapsed() {
        assertEquals("a b\n\nc", TextCleaner.clean("a \t  b\r\n\r\n\r\n\r\nc  "));
    }

    @Test
    void wideGapsInTableRowsBecomeColumnSeparators() {
        assertEquals("Comision | 0,5% | 10", TextCleaner.clean("Comision   0,5%    10"));
    }

    @Test
    void qualityDetectorsFlagBrokenText() {
        assertEquals(2, TextCleaner.garbledCount("a�b�"));
        assertEquals(0, TextCleaner.garbledCount(null));
        assertTrue(TextCleaner.hasControlChars("a\u0001b"));
        assertFalse(TextCleaner.hasControlChars("a\nb\tc"));
        assertTrue(TextCleaner.looksMojibake("Ã¢"));
        assertFalse(TextCleaner.looksMojibake("până în țară, ăâîșț"));
    }
}
