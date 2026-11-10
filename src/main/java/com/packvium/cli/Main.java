package com.packvium.cli;

import com.packvium.serialization.JsonReader;
import com.packvium.serialization.JsonWriter;
import com.packvium.serialization.RequestPacker;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Packing JSON on stdin, result JSON on stdout -- the contract every engine's command-line
 * entry point shares, so the conformance runner can drive this one like the others.
 */
public final class Main {
    private static final int REFUSED = 1;
    private static final int NO_INPUT = 2;

    private Main() {
    }

    public static void main(String[] arguments) throws IOException {
        PrintStream errors = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        try {
            String input = strictUtf8(System.in.readAllBytes());
            if (input.isBlank()) {
                errors.println("Expected packing JSON on stdin");
                System.exit(NO_INPUT);
            }
            String output = JsonWriter.write(RequestPacker.pack(JsonReader.parse(input)));
            PrintStream out = new PrintStream(System.out, false, StandardCharsets.UTF_8);
            out.println(output);
            out.flush();
        } catch (RuntimeException refusal) {
            // Every refusal is the message alone, as the reference engines print it; a stack
            // trace would push the message out of a caller's truncated stderr.
            errors.println(refusal.getMessage());
            System.exit(REFUSED);
        }
    }

    /** Refuses malformed UTF-8 instead of substituting U+FFFD, which would alter ids. */
    private static String strictUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("Malformed UTF-8 characters, possibly incorrectly encoded", malformed);
        }
    }
}
