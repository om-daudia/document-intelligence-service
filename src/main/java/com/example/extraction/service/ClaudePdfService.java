package com.example.extraction.service;

import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.*;

@Service
public class ClaudePdfService {

    private final AnthropicClient client;
    private final String model;

    public ClaudePdfService(AnthropicClient client, @Value("${anthropic.model}") String model) {
        this.client = client;
        this.model = model;
    }

    public String analyze(MultipartFile file, String prompt) throws Exception {
        return analyze(file.getBytes(), file.getOriginalFilename(), prompt);
    }

    public String analyze(byte[] pdfBytes, String title, String prompt) {

        String base64 = Base64.getEncoder().encodeToString(pdfBytes);

        DocumentBlockParam doc = DocumentBlockParam.builder()
                .base64Source(base64)          // also: .urlSource(...) / .textSource(...)
                .title(title == null ? "document" : title)
                .build();

        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(4096L)
                // PDF first, prompt second — Anthropic recommends this ordering
                .addUserMessageOfBlockParams(List.of(
                        ContentBlockParam.ofDocument(doc),
                        ContentBlockParam.ofText(TextBlockParam.builder().text(prompt).build())))
                .build();

        Message response = client.messages().create(params);

        StringBuilder sb = new StringBuilder();
        response.content().stream()
                .flatMap(block -> block.text().stream())
                .forEach(t -> sb.append(t.text()));
        return sb.toString();
    }
}