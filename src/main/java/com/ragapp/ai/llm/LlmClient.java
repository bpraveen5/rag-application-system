package com.ragapp.ai.llm;

import com.ragapp.ai.prompt.PromptBuilder;
import com.ragapp.config.AppProperties;
import com.ragapp.dto.ChatDto;
import com.ragapp.exception.LlmException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmClient {

    private final ChatClient chatClient;
    private final PromptBuilder promptBuilder;
    private final AppProperties props;
    private final MeterRegistry meterRegistry;
    private final Environment env;

    // Reuse a cached thread pool instead of creating a new executor on every call
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * Blocking chat call
     */
    public LlmResponse chat(
            String question,
            List<ChatDto.RetrievedChunk> chunks,
            List<ChatDto.MessageResponse> history,
            String modelOverride,
            Double tempOverride) {

        OllamaOptions options = buildOptions(modelOverride, tempOverride);

        Prompt prompt = promptBuilder.buildWithOptions(
                question,
                chunks,
                history,
                options
        );

        Timer.Sample sample = Timer.start(meterRegistry);

        if (isMockLlmEnabled()) {
            return new LlmResponse(
                    "[mock LLM] Offline response.",
                    0,
                    0,
                    "mock"
            );
        }

        long timeoutMs = resolveTimeoutMs();
        String modelName = resolveModel(modelOverride);

        try {
            log.info(
                    "Calling Ollama model={} chunks={} history={}",
                    modelName,
                    chunks != null ? chunks.size() : 0,
                    history != null ? history.size() : 0
            );

            Future<ChatResponse> future = executor.submit(() ->
                    chatClient.prompt(prompt)
                            .call()
                            .chatResponse()
            );

            ChatResponse response = future.get(timeoutMs, TimeUnit.MILLISECONDS);

            String content = (response != null && response.getResult() != null && response.getResult().getOutput() != null)
                    ? response.getResult().getOutput().getText()
                    : "";

            int inputTokens = safeTokenCount(response, true);
            int outputTokens = safeTokenCount(response, false);

            meterRegistry.counter("rag.llm.tokens.input").increment(inputTokens);
            meterRegistry.counter("rag.llm.tokens.output").increment(outputTokens);

            sample.stop(
                    meterRegistry.timer(
                            "rag.llm.latency",
                            "status", "success",
                            "model", modelName)
            );

            return new LlmResponse(content, inputTokens, outputTokens, modelName);

        } catch (TimeoutException ex) {
            sample.stop(
                    meterRegistry.timer(
                            "rag.llm.latency",
                            "status", "timeout",
                            "model", modelName)
            );
            throw new LlmException("LLM call timed out after " + timeoutMs + "ms", ex);

        } catch (Exception ex) {
            sample.stop(
                    meterRegistry.timer(
                            "rag.llm.latency",
                            "status", "error",
                            "model", modelName)
            );
            throw new LlmException("LLM call failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Streaming chat
     */
    public Flux<String> stream(
            String question,
            List<ChatDto.RetrievedChunk> chunks,
            List<ChatDto.MessageResponse> history,
            String modelOverride,
            Double tempOverride) {

        OllamaOptions options = buildOptions(modelOverride, tempOverride);

        Prompt prompt = promptBuilder.buildWithOptions(
                question,
                chunks,
                history,
                options
        );

        if (isMockLlmEnabled()) {
            return Flux.just("[mock stream]");
        }

        return chatClient.prompt(prompt)
                .stream()
                .chatResponse()
                .filter(r -> r.getResult() != null && r.getResult().getOutput() != null)
                .mapNotNull(r -> r.getResult().getOutput().getText())
                .doOnError(ex -> log.error("Streaming error", ex));
    }

    /**
     * Ollama Options
     */
    private OllamaOptions buildOptions(
            String modelOverride,
            Double tempOverride) {

        double temperature = (tempOverride != null) ? tempOverride : 0.7;

        return OllamaOptions.builder()
                .model(resolveModel(modelOverride))
                .temperature(temperature)
                .keepAlive(env.getProperty("AI_CHAT_KEEP_ALIVE", "30m"))
                .build();
    }

    /**
     * Resolve model
     */
    private String resolveModel(String override) {
        if (override != null && !override.isBlank()) {
            return override;
        }
        return env.getProperty(
                "spring.ai.ollama.chat.options.model",
                "llama3.2:3b"
        );
    }

    /**
     * Token count
     */
    private int safeTokenCount(
            ChatResponse response,
            boolean input) {

        try {
            if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) {
                return 0;
            }
            var usage = response.getMetadata().getUsage();
            Integer tokens = input ? usage.getPromptTokens() : usage.getCompletionTokens();

            return (tokens == null) ? 0 : tokens;
        } catch (Exception ex) {
            return 0;
        }
    }

    /**
     * Timeout
     */
    private long resolveTimeoutMs() {
        return env.getProperty(
                "APP_AI_CALL_TIMEOUT_MS",
                Long.class,
                30000L
        );
    }

    /**
     * Mock mode
     */
    private boolean isMockLlmEnabled() {
        return Boolean.parseBoolean(
                env.getProperty(
                        "APP_AI_MOCK_LLM",
                        "false"
                )
        );
    }

    /**
     * Response wrapper
     */
    public record LlmResponse(
            String content,
            int inputTokens,
            int outputTokens,
            String model) {
    }
}