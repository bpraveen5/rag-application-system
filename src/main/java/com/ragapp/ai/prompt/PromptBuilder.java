package com.ragapp.ai.prompt;

import com.ragapp.config.AppProperties;
import com.ragapp.dto.ChatDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the final Prompt from:
 * 1. System Prompt
 * 2. Conversation History
 * 3. Retrieved Context
 * 4. Current User Question
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromptBuilder {

    private final AppProperties props;

    /**
     * Build prompt without model options.
     */
    public Prompt build(
            String question,
            List<ChatDto.RetrievedChunk> retrievedChunks,
            List<ChatDto.MessageResponse> history) {

        return new Prompt(buildMessages(question, retrievedChunks, history));
    }

    /**
     * Build prompt with Ollama model options.
     */
    public Prompt buildWithOptions(
            String question,
            List<ChatDto.RetrievedChunk> retrievedChunks,
            List<ChatDto.MessageResponse> history,
            OllamaOptions options) {

        List<Message> messages =
                buildMessages(question, retrievedChunks, history);

        return new Prompt(messages, options);
    }

    /**
     * Build the complete message list.
     */
    private List<Message> buildMessages(
            String question,
            List<ChatDto.RetrievedChunk> retrievedChunks,
            List<ChatDto.MessageResponse> history) {

        List<Message> messages = new ArrayList<>();

        messages.add(new SystemMessage(buildSystemPrompt(retrievedChunks)));

        if (history != null) {

            for (ChatDto.MessageResponse msg : history) {

                if ("USER".equalsIgnoreCase(msg.role())) {
                    messages.add(new UserMessage(msg.content()));
                }

                else if ("ASSISTANT".equalsIgnoreCase(msg.role())) {
                    messages.add(new AssistantMessage(msg.content()));
                }
            }
        }

        messages.add(new UserMessage(question));

        log.debug(
                "Built prompt with {} messages ({} history + {} retrieved chunks)",
                messages.size(),
                history != null ? history.size() : 0,
                retrievedChunks != null ? retrievedChunks.size() : 0);

        return messages;
    }

    /**
     * Build the system prompt including retrieved context.
     */
    private String buildSystemPrompt(
            List<ChatDto.RetrievedChunk> chunks) {

        if (chunks == null || chunks.isEmpty()) {

            return props.getPrompts().getSystem()
                    + "\n\n"
                    + props.getPrompts().getNoContext();
        }

        String context =
                chunks.stream()
                        .map(this::buildChunkBlock)
                        .collect(Collectors.joining("\n\n---\n\n"));

        return """
                %s

                ----------------------------

                Retrieved Context

                Use ONLY the information below to answer the user's question.

                Always mention the source document when applicable.

                %s

                ----------------------------

                If the answer is not present in the context, respond:

                "I don't have enough information in the knowledge base to answer that."

                Do not invent information.
                """
                .formatted(
                        props.getPrompts().getSystem(),
                        context);
    }

    /**
     * Format one retrieved chunk.
     */
    private String buildChunkBlock(
            ChatDto.RetrievedChunk chunk) {

        StringBuilder sb = new StringBuilder();

        sb.append("Source: ")
                .append(chunk.documentName());

        if (chunk.pageNumber() != null) {
            sb.append(" | Page ")
                    .append(chunk.pageNumber());
        }

        sb.append(" | Similarity: ")
                .append(String.format("%.2f", chunk.similarityScore()));

        sb.append("\n\n");

        sb.append(chunk.chunkText());

        return sb.toString();
    }
}