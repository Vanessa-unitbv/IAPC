package com.edupulse.ingeststatic.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * app.ingest.embedding-model must follow the model Spring AI really uses. Otherwise changing the model
 * leaves the ingestion fingerprint unchanged, nothing is re-indexed and the store mixes vectors of two models.
 * Runs without a database or an API key: it only loads application.yaml.
 */
class EmbeddingModelBindingTest {

    private static String boundModel(Map<String, Object> overrides) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        // isolate the test from the developer's machine (EMBEDDING_MODEL may be set there)
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        for (PropertySource<?> ps : new YamlPropertySourceLoader().load("application.yaml", new ClassPathResource("application.yaml"))) {
            sources.addLast(ps);
        }
        sources.addFirst(new MapPropertySource("test-overrides", overrides));
        return Binder.get(env).bind("app.ingest.embedding-model", String.class).get();
    }

    @Test
    void defaultsToTheSpringAiDefaultModel() throws IOException {
        assertEquals("text-embedding-3-small", boundModel(Map.of()));
    }

    @Test
    void followsTheModelConfiguredForSpringAi() throws IOException {
        // e.g. SPRING_AI_OPENAI_EMBEDDING_OPTIONS_MODEL set directly in the environment
        assertEquals("text-embedding-3-large",
                boundModel(Map.of("spring.ai.openai.embedding.options.model", "text-embedding-3-large")));
    }

    @Test
    void followsTheEmbeddingModelVariable() throws IOException {
        // EMBEDDING_MODEL from .env or docker-compose
        assertEquals("text-embedding-3-large", boundModel(Map.of("EMBEDDING_MODEL", "text-embedding-3-large")));
    }
}
