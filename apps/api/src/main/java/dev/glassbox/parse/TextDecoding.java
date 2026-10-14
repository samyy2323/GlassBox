package dev.glassbox.parse;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Turns the bytes of a text file into a String without guessing wildly: BOM, then strict UTF-8, then Windows-1252. */
final class TextDecoding {
    private TextDecoding() {}

    static boolean hasBom(byte[] d) {
        return (d.length >= 3 && (d[0] & 0xff) == 0xEF && (d[1] & 0xff) == 0xBB && (d[2] & 0xff) == 0xBF)
                || (d.length >= 2 && (d[0] & 0xff) == 0xFF && (d[1] & 0xff) == 0xFE)
                || (d.length >= 2 && (d[0] & 0xff) == 0xFE && (d[1] & 0xff) == 0xFF);
    }

    static String decode(byte[] d) {
        if (d.length >= 3 && (d[0] & 0xff) == 0xEF && (d[1] & 0xff) == 0xBB && (d[2] & 0xff) == 0xBF) {
            return new String(d, 3, d.length - 3, StandardCharsets.UTF_8);
        }
        if (d.length >= 2 && (d[0] & 0xff) == 0xFF && (d[1] & 0xff) == 0xFE) return new String(d, 2, d.length - 2, StandardCharsets.UTF_16LE);
        if (d.length >= 2 && (d[0] & 0xff) == 0xFE && (d[1] & 0xff) == 0xFF) return new String(d, 2, d.length - 2, StandardCharsets.UTF_16BE);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(d)).toString();
        } catch (CharacterCodingException e) {
            return new String(d, Charset.forName("windows-1252"));
        }
    }
}
