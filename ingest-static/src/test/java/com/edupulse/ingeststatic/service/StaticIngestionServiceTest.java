package com.edupulse.ingeststatic.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticIngestionServiceTest {

    @Test
    void longWordFlagIgnoresUrlsEmailsAndDomains() {
        assertFalse(StaticIngestionService.hasVeryLongWord(
                "Visit https://arbitrobancariofinanziario.it/contact or www.arbitrobancariofinanziario.it"));
        assertFalse(StaticIngestionService.hasVeryLongWord("Contact support@arbitrobancariofinanziario.it."));
        assertFalse(StaticIngestionService.hasVeryLongWord("arbitrobancariofinanziario.it"));
    }

    @Test
    void longWordFlagStillDetectsUnusuallyLongWords() {
        assertTrue(StaticIngestionService.hasVeryLongWord(
                "A supercalifragilisticexpialidociousword appears in this text."));
    }
}
