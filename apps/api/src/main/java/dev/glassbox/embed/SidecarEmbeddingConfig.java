package dev.glassbox.embed;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The three local models the sidecar serves; each has a partial HNSW index (migration V3). */
@Configuration
@ConditionalOnProperty(name = "glassbox.embed.sidecar.enabled", havingValue = "true", matchIfMissing = true)
public class SidecarEmbeddingConfig {
    @Bean
    EmbeddingProvider bgeSmall(SidecarClient client, @Value("${glassbox.embed.batch-size}") int batch) {
        return new SidecarEmbeddingProvider(client, batch, "bge-small-en-v1.5", 384);
    }

    @Bean
    EmbeddingProvider bgeBase(SidecarClient client, @Value("${glassbox.embed.batch-size}") int batch) {
        return new SidecarEmbeddingProvider(client, batch, "bge-base-en-v1.5", 768);
    }

    @Bean
    EmbeddingProvider miniLm(SidecarClient client, @Value("${glassbox.embed.batch-size}") int batch) {
        return new SidecarEmbeddingProvider(client, batch, "all-MiniLM-L6-v2", 384);
    }
}
