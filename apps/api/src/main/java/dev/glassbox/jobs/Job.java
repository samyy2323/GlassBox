package dev.glassbox.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public record Job(UUID id, UUID workspaceId, String type, JsonNode payload, int attempts) {}
