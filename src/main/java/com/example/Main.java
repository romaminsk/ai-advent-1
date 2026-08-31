package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class Main {

    private static final String DEFAULT_PROMPT = "Расскажи анекдот про вайбкодера";

    public static void main(String[] args) {
        String prompt = args.length > 0
                ? String.join(" ", args)
                : DEFAULT_PROMPT;

        String apiKey = System.getenv("LLM_API_KEY");
        String apiUrl = System.getenv("LLM_API_URL");
        String model = System.getenv("LLM_MODEL");

        if (apiKey == null || apiKey.isBlank()
                || apiUrl == null || apiUrl.isBlank()
                || model == null || model.isBlank()) {
            System.err.println("Ошибка: не заданы переменные окружения.");
            System.err.println("Требуются:");
            System.err.println("  LLM_API_KEY  — API-ключ провайдера LLM");
            System.err.println("  LLM_API_URL  — полный URL chat/completions endpoint");
            System.err.println("  LLM_MODEL    — идентификатор модели");
            System.err.println("Пример:");
            System.err.println("  export LLM_API_KEY=\"sk-...\"");
            System.err.println("  export LLM_API_URL=\"https://api.openai.com/v1/chat/completions\"");
            System.err.println("  export LLM_MODEL=\"gpt-4o-mini\"");
            System.exit(1);
        }

        try {
            String answer = sendChatRequest(apiUrl, apiKey, model, prompt);
            System.out.println(answer);
        } catch (Exception e) {
            System.err.println("Ошибка при выполнении запроса: " + e.getMessage());
            System.exit(1);
        }
    }

    private static String sendChatRequest(String apiUrl, String apiKey, String model, String prompt)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);

        ArrayNode messages = root.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", prompt);

        String requestBody = mapper.writeValueAsString(root);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(120))
                .build();

        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode content = mapper.readTree(response.body())
                .path("choices")
                .path(0)
                .path("message")
                .path("content");

        if (content.isMissingNode() || content.asText().isBlank()) {
            throw new IllegalStateException(
                    "В ответе нет choices[0].message.content: " + response.body());
        }
        return content.asText();
    }
}
