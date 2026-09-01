package com.example;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.nio.charset.StandardCharsets;

public class Day2 {

    private static final String STOP_SEQUENCE = "[КОНЕЦ]";
    private static final int JSON_ATTEMPTS = 3;

    private static final List<String> VALID_MODES =
            List.of("free", "json", "limited", "stop", "all");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String JSON_INSTRUCTION =
            "Верни ответ строго в формате JSON: без Markdown, без пояснений, "
                    + "без тройных обратных кавычек и без какого-либо текста до или после JSON.\n"
                    + "Запрос пользователя может быть на любую тему — структура ответа всегда одна и та же.\n"
                    + "Структура ответа:\n"
                    + "{\n"
                    + "  \"title\": \"Краткое название темы\",\n"
                    + "  \"items\": [\n"
                    + "    {\"name\": \"Название пункта\", \"description\": \"Краткое описание пункта\", \"order\": 1}\n"
                    + "  ]\n"
                    + "}\n"
                    + "Требования:\n"
                    + "- title — непустая строка;\n"
                    + "- items — массив пунктов, каждый элемент содержит ровно три поля: "
                    + "name (непустая строка), description (непустая строка), "
                    + "order (целое положительное число, порядковый номер пункта);\n"
                    + "- не добавляй никаких других полей ни в корневой объект, ни в элементы массива;\n"
                    + "- содержимое пунктов должно отвечать на запрос пользователя по существу.";

    private record Config(String apiUrl, String apiKey, String model) {
    }

    private record Arguments(String mode, String prompt) {
    }

    public static void main(String[] args) {
        Arguments arguments = parseArguments(args);
        Config config = loadConfig();

        try {
            switch (arguments.mode()) {
                case "free" -> runFree(config, arguments.prompt());
                case "json" -> runJson(config, arguments.prompt());
                case "limited" -> runLimited(config, arguments.prompt());
                case "stop" -> runStop(config, arguments.prompt());
                case "all" -> runAll(config, arguments.prompt());
                default -> throw new IllegalStateException("Неизвестный режим: " + arguments.mode());
            }
        } catch (HttpTimeoutException e) {
            System.err.println("Превышен тайм-аут HTTP-запроса: " + e.getMessage());
            System.exit(1);
        } catch (JsonProcessingException e) {
            System.err.println("Ошибка разбора JSON в ответе API: " + e.getMessage());
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Запрос был прерван.");
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Ошибка при выполнении: " + e.getMessage());
            System.exit(1);
        }
    }

    private static Arguments parseArguments(String[] args) {
        String mode = "all";
        String prompt = null;

        for (String arg : args) {
            if (arg.startsWith("--mode=")) {
                mode = arg.substring("--mode=".length()).trim().toLowerCase(Locale.ROOT);
            } else if (arg.startsWith("--prompt=")) {
                prompt = arg.substring("--prompt=".length()).trim();
            } else {
                System.err.println("Предупреждение: неизвестный аргумент \"" + arg + "\" игнорируется.");
            }
        }

        if (!VALID_MODES.contains(mode)) {
            System.err.println("Неизвестный режим: " + mode);
            System.err.println("Допустимые режимы:");
            System.err.println("  --mode=free     — без ограничений");
            System.err.println("  --mode=json     — строгий JSON-формат, 3 попытки");
            System.err.println("  --mode=limited  — ограничение формата и длины");
            System.err.println("  --mode=stop     — остановка по stop sequence");
            System.err.println("  --mode=all      — все режимы подряд (по умолчанию)");
            System.exit(1);
        }

        if (prompt == null || prompt.isBlank()) {
            prompt = readInteractivePrompt();
        }

        return new Arguments(mode, prompt);
    }

    private static String readInteractivePrompt() {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.println();
            System.out.println("Аргумент --prompt не задан. Введите текст запроса и нажмите Enter:");
            System.out.print("> ");
            System.out.flush();
            String line;
            try {
                line = reader.readLine();
            } catch (IOException e) {
                System.err.println("Не удалось прочитать ввод: " + e.getMessage());
                System.exit(1);
                return "";
            }
            if (line == null) {
                System.err.println();
                System.err.println("Ошибка: поток ввода закрыт, запрос прочитать не удалось.");
                System.err.println("Передайте запрос аргументом: --prompt=\"текст запроса\"");
                System.exit(1);
                return "";
            }
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
            System.out.println("Запрос не может быть пустым. Попробуйте ещё раз.");
        }
    }

    private static Config loadConfig() {
        String apiKey = System.getenv("LLM_API_KEY");
        String apiUrl = System.getenv("LLM_API_URL");
        String model = System.getenv("LLM_MODEL");

        List<String> missing = new ArrayList<>();
        if (apiKey == null || apiKey.isBlank()) {
            missing.add("LLM_API_KEY");
        }
        if (apiUrl == null || apiUrl.isBlank()) {
            missing.add("LLM_API_URL");
        }
        if (model == null || model.isBlank()) {
            missing.add("LLM_MODEL");
        }

        if (!missing.isEmpty()) {
            System.err.println("Ошибка: не заданы переменные окружения: " + String.join(", ", missing));
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

        return new Config(apiUrl, apiKey, model);
    }

    private static void runAll(Config config, String prompt) throws IOException, InterruptedException {
        System.out.println("=== ИСХОДНЫЙ ЗАПРОС ===");
        System.out.println(prompt);
        System.out.println();
        runFree(config, prompt);
        System.out.println();
        runJson(config, prompt);
        System.out.println();
        runLimited(config, prompt);
        System.out.println();
        runStop(config, prompt);
    }

    private static void runFree(Config config, String prompt) throws IOException, InterruptedException {
        System.out.println("=== РЕЖИМ FREE: БЕЗ ОГРАНИЧЕНИЙ ===");
        String answer = sendChatRequest(config, prompt, null, null, null, "Ждём ответ модели");
        System.out.println(answer);
    }

    private static void runJson(Config config, String prompt) throws IOException, InterruptedException {
        System.out.println("=== РЕЖИМ JSON: СТАБИЛЬНЫЙ ФОРМАТ ===");
        String jsonPrompt = prompt + "\n\n" + JSON_INSTRUCTION;

        for (int attempt = 1; attempt <= JSON_ATTEMPTS; attempt++) {
            System.out.println("--- JSON-ответ " + attempt + " ---");
            try {
                String content = sendChatRequest(config, jsonPrompt, null, null, 0.0,
                        "Запрос " + attempt + "/" + JSON_ATTEMPTS);
                JsonNode root = MAPPER.readTree(content);
                String error = validateJsonStructure(root);
                if (error != null) {
                    System.out.println("Ошибка проверки структуры: " + error);
                    System.out.println("Ответ модели:");
                    System.out.println(content);
                    System.out.println("Проверка структуры: ERROR");
                } else {
                    System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
                    System.out.println("Проверка структуры: OK");
                }
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                System.out.println("Ошибка запроса: " + e.getMessage());
                System.out.println("Проверка структуры: ERROR");
            }
        }
    }

    private static void runLimited(Config config, String prompt) throws IOException, InterruptedException {
        System.out.println("=== РЕЖИМ LIMITED: ОГРАНИЧЕНИЕ ФОРМАТА И ДЛИНЫ ===");
        String limitedPrompt = prompt + "\n\nОтветь по правилам:\n"
                + "- ответ должен содержать не более 60 слов;\n"
                + "- формат ответа — короткий нумерованный список;\n"
                + "- список должен содержать ровно 3 пункта;\n"
                + "- каждый пункт должен состоять из одного предложения;\n"
                + "- не добавляй вступление и заключение.";

        String answer = sendChatRequest(config, limitedPrompt, 120, null, null, "Ждём ответ модели");
        System.out.println(answer);
        System.out.println("Количество слов: " + countWords(answer));
    }

    private static void runStop(Config config, String prompt) throws IOException, InterruptedException {
        System.out.println("=== РЕЖИМ STOP: ОСТАНОВКА ПО ПОСЛЕДОВАТЕЛЬНОСТИ ===");
        String stopPrompt = prompt + "\n\nОтветь строго в формате:\n"
                + "Ответ: <краткий ответ по существу запроса>\n"
                + "Детали: <краткие пояснения>\n"
                + "Итог: <краткий вывод>\n"
                + STOP_SEQUENCE;

        String answer = sendChatRequest(config, stopPrompt, 200, List.of(STOP_SEQUENCE), null,
                "Ждём ответ модели");
        System.out.println(answer);
        if (!answer.contains(STOP_SEQUENCE)) {
            System.out.println();
            System.out.println("(Последовательность " + STOP_SEQUENCE + " не включена в ответ — "
                    + "API обычно не возвращает сработавшую stop sequence в content. Это не ошибка.)");
        }
    }

    private static String sendChatRequest(Config config,
                                          String prompt,
                                          Integer maxTokens,
                                          List<String> stopSequences,
                                          Double temperature,
                                          String loaderLabel) throws IOException, InterruptedException {
        String requestBody = buildRequestBody(config.model(), prompt, maxTokens, stopSequences, temperature);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.apiUrl()))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(120))
                .build();

        Thread loader = startLoader(loaderLabel);
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } finally {
            stopLoader(loader);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode body = MAPPER.readTree(response.body());
        return extractAnswer(body, response.body());
    }

    private static String buildRequestBody(String model,
                                           String prompt,
                                           Integer maxTokens,
                                           List<String> stopSequences,
                                           Double temperature) throws JsonProcessingException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);

        ArrayNode messages = root.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", prompt);

        if (maxTokens != null) {
            root.put("max_tokens", maxTokens);
        }
        if (temperature != null) {
            root.put("temperature", temperature);
        }
        if (stopSequences != null && !stopSequences.isEmpty()) {
            ArrayNode stop = root.putArray("stop");
            for (String sequence : stopSequences) {
                stop.add(sequence);
            }
        }

        return MAPPER.writeValueAsString(root);
    }

    private static String extractAnswer(JsonNode body, String rawBody) {
        JsonNode message = body.path("choices").path(0).path("message");

        String content = message.path("content").asText("");
        if (content.isBlank()) {
            content = message.path("reasoning_content").asText("");
        }

        if (content.isBlank()) {
            throw new IllegalStateException(
                    "В ответе нет choices[0].message.content и choices[0].message.reasoning_content: "
                            + rawBody);
        }
        return content;
    }

    private static String validateJsonStructure(JsonNode root) {
        if (root == null || !root.isObject()) {
            return "Корневой элемент не является JSON-объектом.";
        }

        JsonNode title = root.get("title");
        if (title == null || !title.isTextual() || title.asText().isBlank()) {
            return "Поле title отсутствует или не является непустой строкой.";
        }

        JsonNode items = root.get("items");
        if (items == null || !items.isArray()) {
            return "Поле items отсутствует или не является массивом.";
        }

        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            String label = "Пункт №" + (i + 1) + ": ";
            if (item == null || !item.isObject()) {
                return label + "элемент не является JSON-объектом.";
            }
            if (item.size() != 3) {
                return label + "ожидается ровно 3 поля (name, description, order), найдено " + item.size() + ".";
            }
            JsonNode name = item.get("name");
            if (name == null || !name.isTextual() || name.asText().isBlank()) {
                return label + "поле name отсутствует или не является непустой строкой.";
            }
            JsonNode description = item.get("description");
            if (description == null || !description.isTextual() || description.asText().isBlank()) {
                return label + "поле description отсутствует или не является непустой строкой.";
            }
            JsonNode order = item.get("order");
            if (order == null || !order.isIntegralNumber()
                    || !order.canConvertToInt() || order.asInt() <= 0) {
                return label + "поле order должно быть целым положительным числом.";
            }
        }

        return null;
    }

    private static int countWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }

    private static Thread startLoader(String label) {
        Thread loader = new Thread(() -> {
            String frames = "|/-\\";
            long start = System.currentTimeMillis();
            int i = 0;
            while (!Thread.currentThread().isInterrupted()) {
                long seconds = (System.currentTimeMillis() - start) / 1000;
                System.out.print("\r" + label + " " + frames.charAt(i % frames.length())
                        + " " + seconds + " с   ");
                System.out.flush();
                i++;
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "day2-loader");
        loader.setDaemon(true);
        loader.start();
        return loader;
    }

    private static void stopLoader(Thread loader) {
        if (loader == null || !loader.isAlive()) {
            return;
        }
        loader.interrupt();
        try {
            loader.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.print("\r" + " ".repeat(100) + "\r");
        System.out.flush();
    }
}
