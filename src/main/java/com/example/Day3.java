package com.example;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class Day3 {

    private static final String DEFAULT_TASK =
            "Три подруги — Аня, Белла и Вика — носят платья разных цветов: "
                    + "красное, белое и синее. Известно:\n"
                    + "1. Аня не в красном.\n"
                    + "2. Девушка в белом сказала Белле: «Тебе идёт синее».\n"
                    + "Определи, кто в каком платье. Затем второй вопрос: "
                    + "если красное платье стоит 90 рублей, а каждое следующее "
                    + "(белое, синее) на 10% дешевле предыдущего, "
                    + "сколько стоят все три платья вместе? Округли до целого.";

    private static final double SOLUTION_TEMPERATURE = 0.2;
    private static final double META_PROMPT_TEMPERATURE = 0.7;
    private static final int PROMPT_PREVIEW_LENGTH = 300;

    private static final List<String> VALID_MODES =
            List.of("direct", "cot", "meta", "experts", "all");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String COT_INSTRUCTION =
            "Решай пошагово: распиши цепочку рассуждений, затем дай финальный ответ.";

    private static final String META_PROMPT_INSTRUCTION =
            "Составь максимально эффективный промпт для решения следующей задачи. "
                    + "Верни только текст промпта: без пояснений, без кавычек вокруг всего текста, "
                    + "без markdown-оформления и без какого-либо текста до или после промпта.\n\n"
                    + "Задача:\n";

    private static final String EXPERTS_SYSTEM_PROMPT =
            "Ты — рабочая группа из трёх экспертов, которая совместно решает задачи.\n"
                    + "Участники группы:\n"
                    + "- Аналитик — разбирает условие, выделяет факты и ограничения;\n"
                    + "- Инженер — строит пошаговое решение на основе анализа Аналитика;\n"
                    + "- Критик — проверяет решение Инженера, указывает на ошибки и слабые места.\n"
                    + "Каждый эксперт по очереди даёт своё решение или разбор: "
                    + "сначала Аналитик, затем Инженер, затем Критик.\n"
                    + "После выступления всех экспертов сформируй согласованный итоговый ответ группы.\n"
                    + "Строго соблюдай формат ответа:\n"
                    + "Аналитик: <разбор условия>\n"
                    + "Инженер: <пошаговое решение>\n"
                    + "Критик: <проверка решения и замечания>\n"
                    + "Итог: <согласованный итоговый ответ группы с обоснованием>\n"
                    + "Отвечай на русском языке.";

    private record Config(String apiUrl, String apiKey, String model) {
    }

    private record Arguments(String mode, String task) {
    }

    private record Answer(String text, boolean fromReasoning, String finishReason) {
    }

    public static void main(String[] args) {
        Arguments arguments = parseArguments(args);
        Config config = loadConfig();

        try {
            switch (arguments.mode()) {
                case "direct" -> runDirect(config, arguments.task());
                case "cot" -> runCot(config, arguments.task());
                case "meta" -> runMeta(config, arguments.task());
                case "experts" -> runExperts(config, arguments.task());
                case "all" -> runAll(config, arguments.task());
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
        String task = null;

        for (String arg : args) {
            if (arg.startsWith("--mode=")) {
                mode = arg.substring("--mode=".length()).trim().toLowerCase(Locale.ROOT);
            } else if (arg.startsWith("--task=")) {
                task = arg.substring("--task=".length()).trim();
            } else {
                System.err.println("Предупреждение: неизвестный аргумент \"" + arg + "\" игнорируется.");
            }
        }

        if (!VALID_MODES.contains(mode)) {
            System.err.println("Неизвестный режим: " + mode);
            System.err.println("Допустимые режимы:");
            System.err.println("  --mode=direct   — задача как есть, без дополнительных инструкций");
            System.err.println("  --mode=cot      — пошаговое решение (chain of thought)");
            System.err.println("  --mode=meta     — модель сначала составляет промпт, затем решает задачу им");
            System.err.println("  --mode=experts  — совещание экспертов: Аналитик, Инженер, Критик");
            System.err.println("  --mode=all      — все режимы подряд и сравнение (по умолчанию)");
            System.exit(1);
        }

        if (task == null || task.isBlank()) {
            task = DEFAULT_TASK;
        }

        return new Arguments(mode, task);
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

    private static void runAll(Config config, String task) throws IOException, InterruptedException {
        System.out.println("=== ИСХОДНАЯ ЗАДАЧА ===");
        System.out.println(task);
        System.out.println();

        String directAnswer = runDirect(config, task);
        System.out.println();
        String cotAnswer = runCot(config, task);
        System.out.println();
        String metaAnswer = runMeta(config, task);
        System.out.println();
        String expertsAnswer = runExperts(config, task);
        System.out.println();

        runComparison(config, task, directAnswer, cotAnswer, metaAnswer, expertsAnswer);
    }

    private static String runDirect(Config config, String task) throws IOException, InterruptedException {
        System.out.println("=== РЕЖИМ DIRECT: ЗАДАЧА КАК ЕСТЬ ===");
        System.out.println("--- Отправленный промпт (кратко) ---");
        System.out.println(shorten(task));
        System.out.println();

        Answer answer = sendChatRequest(config, task, null, SOLUTION_TEMPERATURE, "Ждём ответ модели");
        printAnswerWithWordCount(answer);
        return answer.text();
    }

    private static String runCot(Config config, String task) throws IOException, InterruptedException {
        System.out.println("=== РЕЖИМ COT: ПОШАГОВОЕ РЕШЕНИЕ ===");
        String cotPrompt = task + "\n\n" + COT_INSTRUCTION;
        System.out.println("--- Отправленный промпт (кратко) ---");
        System.out.println(shorten(cotPrompt));
        System.out.println();

        Answer answer = sendChatRequest(config, cotPrompt, null, SOLUTION_TEMPERATURE, "Ждём ответ модели");
        printAnswerWithWordCount(answer);
        return answer.text();
    }

    private static String runMeta(Config config, String task) throws IOException, InterruptedException {
        System.out.println("=== РЕЖИМ META: ПРОМПТ СОСТАВЛЯЕТ САМА МОДЕЛЬ ===");

        System.out.println("--- Запрос 1/2: генерация промпта (temperature=0.7) ---");
        System.out.println("Отправленный промпт (кратко):");
        System.out.println(shorten(META_PROMPT_INSTRUCTION + task));
        System.out.println();

        Answer generated = sendChatRequest(config, META_PROMPT_INSTRUCTION + task, null,
                META_PROMPT_TEMPERATURE, "Модель составляет промпт");
        String generatedPrompt = cleanupGeneratedPrompt(generated.text());
        System.out.println("Сгенерированный промпт:");
        System.out.println(generatedPrompt);
        if (generated.fromReasoning()) {
            System.out.println();
            System.out.println("(Внимание: поле content пусто — в качестве промпта использованы "
                    + "размышления модели (reasoning_content), финальный промпт не сформирован.)");
        }
        System.out.println();

        System.out.println("--- Запрос 2/2: решение задачи сгенерированным промптом (temperature=0.2) ---");
        System.out.println("Отправленный промпт (кратко):");
        System.out.println(shorten(generatedPrompt));
        System.out.println();

        Answer answer = sendChatRequest(config, generatedPrompt, null, SOLUTION_TEMPERATURE,
                "Ждём ответ модели");
        printAnswerWithWordCount(answer);
        return answer.text();
    }

    private static String runExperts(Config config, String task) throws IOException, InterruptedException {
        System.out.println("=== РЕЖИМ EXPERTS: СОВЕЩАНИЕ ЭКСПЕРТОВ ===");
        System.out.println("--- Отправленный промпт (кратко) ---");
        System.out.println("system (кратко):");
        System.out.println(shorten(EXPERTS_SYSTEM_PROMPT));
        System.out.println("user (кратко):");
        System.out.println(shorten(task));
        System.out.println();

        Answer answer = sendChatRequest(config, task, EXPERTS_SYSTEM_PROMPT, SOLUTION_TEMPERATURE,
                "Совещание экспертов");
        printAnswerWithWordCount(answer);
        return answer.text();
    }

    private static void runComparison(Config config,
                                      String task,
                                      String directAnswer,
                                      String cotAnswer,
                                      String metaAnswer,
                                      String expertsAnswer) throws IOException, InterruptedException {
        System.out.println("=== СРАВНЕНИЕ ===");

        StringBuilder comparisonPrompt = new StringBuilder();
        comparisonPrompt.append("Задача:\n").append(task).append("\n\n");
        comparisonPrompt.append("Ниже — четыре ответа на одну и ту же задачу, ")
                .append("полученные разными способами промптинга.\n\n");
        comparisonPrompt.append("Способ 1 — direct (задача без дополнительных инструкций):\n")
                .append(directAnswer).append("\n\n");
        comparisonPrompt.append("Способ 2 — cot (пошаговое решение):\n")
                .append(cotAnswer).append("\n\n");
        comparisonPrompt.append("Способ 3 — meta (промпт, составленный самой моделью):\n")
                .append(metaAnswer).append("\n\n");
        comparisonPrompt.append("Способ 4 — experts (группа экспертов):\n")
                .append(expertsAnswer).append("\n\n");
        comparisonPrompt.append("Сравни эти четыре ответа и ответь:\n")
                .append("1. Совпадают ли ответы между собой и дают ли они один и тот же итоговый ")
                .append("результат решения задачи.\n")
                .append("2. Какой способ дал наиболее точный и полный результат и за счёт чего.\n")
                .append("3. Краткий вывод: какой способ рассуждения лучше подходит для подобных задач.\n")
                .append("Отвечай на русском языке.");

        System.out.println("--- Отправленный промпт (кратко) ---");
        System.out.println(shorten(comparisonPrompt.toString()));
        System.out.println();

        Answer answer = sendChatRequest(config, comparisonPrompt.toString(), null, SOLUTION_TEMPERATURE,
                "Модель сравнивает ответы");
        printAnswerWithWordCount(answer);
    }

    private static void printAnswerWithWordCount(Answer answer) {
        System.out.println("--- Ответ модели ---");
        System.out.println(answer.text());
        if (answer.fromReasoning()) {
            System.out.println();
            System.out.println("(Внимание: поле content пусто — показаны размышления модели "
                    + "(reasoning_content), финальный ответ не сформирован.)");
        }
        System.out.println();
        System.out.println("Количество слов: " + countWords(answer.text()));
    }

    private static String cleanupGeneratedPrompt(String text) {
        String result = text.trim();
        if (result.startsWith("```")) {
            int firstNewline = result.indexOf('\n');
            if (firstNewline >= 0) {
                result = result.substring(firstNewline + 1);
            }
            if (result.endsWith("```")) {
                result = result.substring(0, result.length() - 3);
            }
            result = result.trim();
        }
        if (result.length() >= 2
                && ((result.startsWith("\"") && result.endsWith("\""))
                || (result.startsWith("«") && result.endsWith("»"))
                || (result.startsWith("“") && result.endsWith("”")))) {
            result = result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    private static Answer sendChatRequest(Config config,
                                          String prompt,
                                          String systemPrompt,
                                          double temperature,
                                          String loaderLabel) throws IOException, InterruptedException {
        String requestBody = buildRequestBody(config.model(), prompt, systemPrompt, temperature);

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
        return buildAnswer(body, response.body());
    }

    private static String buildRequestBody(String model,
                                           String prompt,
                                           String systemPrompt,
                                           double temperature) throws JsonProcessingException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);

        ArrayNode messages = root.putArray("messages");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode systemMessage = messages.addObject();
            systemMessage.put("role", "system");
            systemMessage.put("content", systemPrompt);
        }
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", prompt);

        root.put("temperature", temperature);

        ObjectNode thinking = root.putObject("thinking");
        thinking.put("type", "disabled");

        return MAPPER.writeValueAsString(root);
    }

    private static Answer buildAnswer(JsonNode body, String rawBody) {
        JsonNode choice = body.path("choices").path(0);
        String finishReason = choice.path("finish_reason").asText("");
        JsonNode message = choice.path("message");

        String content = message.path("content").asText("");
        if (!content.isBlank()) {
            return new Answer(content, false, finishReason);
        }

        String reasoning = message.path("reasoning_content").asText("");
        if (!reasoning.isBlank()) {
            return new Answer(reasoning, true, finishReason);
        }

        throw new IllegalStateException(
                "В ответе нет choices[0].message.content и choices[0].message.reasoning_content: "
                        + rawBody);
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= PROMPT_PREVIEW_LENGTH) {
            return text;
        }
        return text.substring(0, PROMPT_PREVIEW_LENGTH) + "\n…(промпт обрезан для краткости)";
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
        }, "day3-loader");
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
