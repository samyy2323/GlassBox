package dev.glassbox.parse;

/** The file is valid but cannot be ingested (no text layer, password protected, ...). Not retried. */
public class UnsupportedDocumentException extends RuntimeException {
    public UnsupportedDocumentException(String message) {
        super(message);
    }
}
