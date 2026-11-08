package com.example.structured_output;

import com.example.structured_output.StructuredOutputController.CodeReview;
import com.example.structured_output.StructuredOutputController.Severity;
import com.example.structured_output.StructuredOutputController.Verdict;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shows the self-correction loop without calling a real LLM: a scripted ChatModel
 * first returns JSON that violates the schema, then a valid reply.
 */
class SelfCorrectingStructuredOutputTests {

    /** "CRITICAL" is not a valid Severity and "verdict" is missing. */
    private static final String INVALID_REPLY = """
            {"summary":"Possible NPE","findings":[
              {"line":3,"severity":"CRITICAL","category":"BUG",
               "issue":"name may be null","suggestion":"use Objects.requireNonNull"}]}
            """;

    private static final String VALID_REPLY = """
            {"summary":"Possible NPE","verdict":"REQUEST_CHANGES","findings":[
              {"line":3,"severity":"HIGH","category":"BUG",
               "issue":"name may be null","suggestion":"use Objects.requireNonNull"}]}
            """;

    @Test
    void validateSchemaFeedsErrorBackAndRetries() {
        ScriptedChatModel model = new ScriptedChatModel(INVALID_REPLY, VALID_REPLY);

        CodeReview review = ChatClient.create(model).prompt()
                .user("Review: String s = name.trim();")
                .call()
                .entity(CodeReview.class, spec -> spec.validateSchema());

        // Two round trips: the first failed validation, the second passed.
        assertThat(model.prompts).hasSize(2);
        assertThat(review.verdict()).isEqualTo(Verdict.REQUEST_CHANGES);
        assertThat(review.findings()).first().extracting(f -> f.severity()).isEqualTo(Severity.HIGH);

        // The retry carried the validation error inside the user message.
        String retryUserText = model.prompts.get(1).getUserMessage().getText();
        assertThat(retryUserText).contains("Output JSON validation failed because of:");
        assertThat(model.prompts.get(0).getUserMessage().getText())
                .doesNotContain("Output JSON validation failed");
    }

    @Test
    void withoutValidateSchemaThereIsNoRetry() {
        ScriptedChatModel model = new ScriptedChatModel(VALID_REPLY);

        ChatClient.create(model).prompt()
                .user("Review: String s = name.trim();")
                .call()
                .entity(CodeReview.class);

        assertThat(model.prompts).hasSize(1);
    }

    /** Returns the scripted replies in order and records every prompt it receives. */
    static final class ScriptedChatModel implements ChatModel {

        final List<Prompt> prompts = new ArrayList<>();
        private final Deque<String> replies;

        ScriptedChatModel(String... replies) {
            this.replies = new ArrayDeque<>(List.of(replies));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(replies.pop()))));
        }
    }
}
