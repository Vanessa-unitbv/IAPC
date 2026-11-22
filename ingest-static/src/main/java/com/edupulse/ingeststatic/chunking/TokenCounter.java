package com.edupulse.ingeststatic.chunking;

/** Counts tokens; abstracted so the splitter can be unit-tested without a tokenizer. */
@FunctionalInterface
public interface TokenCounter {
    int count(String text);
}
