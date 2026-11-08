package com.example.structured_output;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;


@RestController
@RequestMapping("/api/structured")
class StructuredOutputController {

    private static final String REVIEWER_PERSONA = """
            You are a meticulous senior code reviewer.
            Review the submitted code for bugs, security issues, performance and readability.
            Reference line numbers counted from 1 within the submitted snippet.
            """;

    private static final String RELEASE_MANAGER_PERSONA = """
            You are a release manager who turns raw engineering change lists into release notes
            that end users can understand. Rewrite each change from the user's point of view,
            drop purely internal work, and flag anything that requires user action as breaking.
            """;

    /** Explicit advisor: up to 5 retries (6 calls in total) instead of the default 3. */
    private static final int STRICT_MAX_REPEAT_ATTEMPTS = 5;

    private final ChatClient chatClient;
    private final ChatClient strictReviewClient;

    StructuredOutputController(ChatClient.Builder builder) {
        // Shared client: validation advisor is auto-registered per call by validateSchema().
        this.chatClient = builder.clone()
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();

        // Dedicated client: an explicitly registered validation advisor, tied to CodeReview.
        // An explicit registration replaces the one that validateSchema() would auto-register.
        this.strictReviewClient = builder.clone()
                .defaultAdvisors(
                        StructuredOutputValidationAdvisor.builder()
                                .outputType(CodeReview.class)
                                .maxRepeatAttempts(STRICT_MAX_REPEAT_ATTEMPTS)
                                .build(),
                        new SimpleLoggerAdvisor())
                .build();
    }

    /**
     * Dial 1 - prompt-based structured output + self-correction.
     * The JSON format instructions are appended to the prompt, the reply is validated
     * against the CodeReview schema and, if invalid (e.g. severity "CRITICAL" which is
     * not in the enum, or a missing field), the error is fed back to the model.
     */
    @PostMapping("/code-review")
    CodeReview reviewCode(@RequestBody @Valid CodeSubmission submission) {
        return chatClient.prompt()
                .system(REVIEWER_PERSONA)
                .user(u -> u.text("Language: {language}\n\nCode:\n{code}")
                        .param("language", submission.language())
                        .param("code", submission.code()))
                .call()
                .entity(CodeReview.class, spec -> spec.validateSchema());
    }

    /**
     * Dial 2 - provider-native structured output + validation as a safety net.
     * OpenAI receives the schema as {@code response_format}, so the format instructions
     * are removed from the prompt. OpenAI rejects top-level arrays, hence the
     * {@link CommitSuggestions} wrapper record around the list.
     */
    @PostMapping("/commit-messages")
    CommitSuggestions suggestCommitMessages(@RequestBody @Valid CommitRequest request) {
        return chatClient.prompt()
                .user(u -> u.text("""
                                Here is a summary of the code changes I'm about to commit:

                                {changes}

                                Suggest {options} Conventional Commits messages. Keep each
                                subject under 72 characters and use the imperative mood.
                                """)
                        .param("changes", request.changes())
                        .param("options", request.options()))
                .call()
                .entity(CommitSuggestions.class,
                        spec -> spec.useProviderStructuredOutput().validateSchema());
    }

    /**
     * Dial 3 - typed entity plus response metadata.
     * {@code responseEntity} returns both the parsed record and the ChatResponse, whose
     * usage is accumulated across any self-correction retries.
     */
    @PostMapping("/release-notes")
    ReleaseNotesResult draftReleaseNotes(@RequestBody @Valid ReleaseRequest request) {
        String changeList = request.changes().stream()
                .map(change -> "- " + change)
                .collect(Collectors.joining("\n"));

        ResponseEntity<ChatResponse, ReleaseNotes> response = chatClient.prompt()
                .system(RELEASE_MANAGER_PERSONA)
                .user("Version " + request.version() + " changes:\n" + changeList)
                .call()
                .responseEntity(ReleaseNotes.class, spec -> spec.validateSchema());

        return new ReleaseNotesResult(response.entity(), TokenUsage.from(response.response()));
    }

    /**
     * Dial 4 - explicitly configured validation advisor (custom retry budget).
     * No spec consumer needed: the advisor is already part of strictReviewClient.
     */
    @PostMapping("/code-review/strict")
    CodeReview reviewCodeStrict(@RequestBody @Valid CodeSubmission submission) {
        return strictReviewClient.prompt()
                .system(REVIEWER_PERSONA)
                .user(u -> u.text("Language: {language}\n\nCode:\n{code}")
                        .param("language", submission.language())
                        .param("code", submission.code()))
                .call()
                .entity(CodeReview.class);
    }

    // ---------------------------------------------------------------- request DTOs

    record CodeSubmission(@NotBlank String language, @NotBlank String code) {}

    record CommitRequest(@NotBlank String changes, @Min(1) @Max(10) int options) {}

    record ReleaseRequest(@NotBlank String version, @NotEmpty List<@NotBlank String> changes) {}

    // ------------------------------------------------- structured output (LLM) types
    // The JSON schema sent to / validated against the model is generated from these
    // records. Enums become "enum" constraints, so out-of-range values trigger a retry.

    enum Verdict { APPROVE, REQUEST_CHANGES, COMMENT }

    enum Severity { LOW, MEDIUM, HIGH }

    enum Category { BUG, SECURITY, PERFORMANCE, READABILITY, STYLE }

    record Finding(
            @JsonPropertyDescription("1-based line number in the submitted snippet") int line,
            Severity severity,
            Category category,
            @JsonPropertyDescription("What is wrong, in one sentence") String issue,
            @JsonPropertyDescription("Concrete fix, ideally with a code fragment") String suggestion) {}

    record CodeReview(
            @JsonPropertyDescription("One-sentence overall assessment") String summary,
            Verdict verdict,
            List<Finding> findings) {}

    enum CommitType { FEAT, FIX, DOCS, STYLE, REFACTOR, PERF, TEST, BUILD, CI, CHORE }

    record CommitSuggestion(
            CommitType type,
            @JsonPropertyDescription("Optional scope, e.g. 'auth'; empty string if none") String scope,
            @JsonPropertyDescription("Imperative subject under 72 characters") String subject) {

        /** Renders e.g. {@code feat(auth): add token refresh}. */
        public String message() {
            String prefix = type.name().toLowerCase();
            return (scope == null || scope.isBlank())
                    ? prefix + ": " + subject
                    : prefix + "(" + scope + "): " + subject;
        }
    }

    /** Wrapper record: OpenAI native structured output rejects top-level arrays. */
    record CommitSuggestions(List<CommitSuggestion> suggestions) {}

    record ReleaseNotes(
            @JsonPropertyDescription("One-line headline summarising the theme of the release") String headline,
            List<String> added,
            List<String> improved,
            List<String> fixed,
            @JsonPropertyDescription("Changes that need user action, each including the required step")
            List<String> breakingChanges) {}

    // ------------------------------------------------------------- response DTOs

    record TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        static TokenUsage from(ChatResponse response) {
            if (response == null || response.getMetadata() == null) {
                return new TokenUsage(null, null, null);
            }
            Usage usage = response.getMetadata().getUsage();
            return new TokenUsage(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
        }
    }

    record ReleaseNotesResult(ReleaseNotes notes, TokenUsage usage) {}
}
