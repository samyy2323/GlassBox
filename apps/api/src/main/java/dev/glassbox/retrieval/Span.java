package dev.glassbox.retrieval;

import java.util.Map;

/** A timed pipeline stage. Every stage writes one so X-Ray can show where the time and the candidates went. */
public record Span(String stage, long millis, Map<String, Object> detail) {}
