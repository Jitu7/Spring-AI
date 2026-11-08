package com.example.structured_output;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/dev")
class DevAssistantController {

    private static final String MENTOR_PERSONA = """
            You are a pragmatic staff engineer mentoring a teammate.
            Answer in at most five sentences, favour concrete examples over theory,
            and finish with one actionable next step.
            """;

    private static final String COMMIT_MESSAGE_TEMPLATE = """
            Here is a summary of the code changes I'm about to commit:

            {changes}

            Suggest {options} commit messages that follow the Conventional Commits format
            (type(scope): subject). Keep each subject under 72 characters, use the
            imperative mood, and list one suggestion per line with no extra commentary.
            """;

    private final ChatClient chatClient;
    private final Resource releaseNotesSystemPrompt;

    DevAssistantController(ChatClient.Builder builder,
                           @Value("classpath:/prompts/release-notes-system.st")
                           Resource releaseNotesSystemPrompt) {
        this.chatClient = builder
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
        this.releaseNotesSystemPrompt = releaseNotesSystemPrompt;
    }

    /** Hand-built system and user messages. */
    @PostMapping("/ask")
    Reply askMentor(@RequestBody @Valid Question question) {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(MENTOR_PERSONA),
                new UserMessage(question.text())));

        return new Reply(chatClient.prompt(prompt).call().content());
    }

    /** Inline template, placeholders filled from the request body. */
    @PostMapping("/commit-messages")
    Reply suggestCommitMessages(@RequestBody @Valid CommitRequest request) {
        Message userMessage = PromptTemplate.builder()
                .template(COMMIT_MESSAGE_TEMPLATE)
                .build()
                .createMessage(Map.of(
                        "changes", request.changes(),
                        "options", request.options()));

        return new Reply(chatClient.prompt().messages(userMessage).call().content());
    }

    /** System prompt kept in src/main/resources/prompts. */
    @PostMapping("/release-notes")
    Reply draftReleaseNotes(@RequestBody @Valid ReleaseRequest request) {
        String changeList = request.changes().stream()
                .map(change -> "- " + change)
                .collect(Collectors.joining("\n"));

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(releaseNotesSystemPrompt),
                new UserMessage("Version " + request.version() + " changes:\n" + changeList)));

        return new Reply(chatClient.prompt(prompt).call().content());
    }

    record Question(@NotBlank String text) {}

    record CommitRequest(@NotBlank String changes, @Min(1) @Max(10) int options) {}

    record ReleaseRequest(@NotBlank String version, @NotEmpty List<@NotBlank String> changes) {}

    record Reply(String content) {}
}
