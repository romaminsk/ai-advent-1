package com.example;

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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class Day4 {

    // Три температуры для сравнения: базовая, средняя и высокая.
    private static final double[] TEMPERATURES = {0.0, 0.7, 1.2};

    // Пять прогонов на каждую температуру: всего 15 запросов.
    private static final int RUNS_PER_TEMPERATURE = 5;

    // Единый комбинированный запрос: точность, креативность и разнообразие в одном ответе.
    private static final String DEFAULT_TASK =
            "Выполни три независимые части и строго соблюдай формат ответа.\n\n"
                    + "1. ТОЧНОСТЬ\n"
                    + "Реши пять заданий:\n"
                    + "A) 37 × 24 − 19\n"
                    + "B) Следующее число последовательности: 2, 4, 8, 16, ...\n"
                    + "C) Столица Франции\n"
                    + "D) Сколько градусов в прямом угле\n"
                    + "E) Химический символ воды\n\n"
                    + "2. КРЕАТИВНОСТЬ\n"
                    + "Придумай одну образную метафору о кофе длиной от 6 до 10 слов.\n"
                    + "Не используй слова «бодрость», «энергия», «утро», "
                    + "«аромат» и «напиток».\n\n"
                    + "3. РАЗНООБРАЗИЕ\n"
                    + "Придумай пять разных названий из двух слов для кофейни в космосе.\n"
                    + "Названия не должны повторять слова друг друга.\n\n"
                    + "Ответь строго в следующем формате:\n\n"
                    + "ТОЧНОСТЬ: A=<ответ>; B=<ответ>; C=<ответ>; "
                    + "D=<ответ>; E=<ответ>\n"
                    + "КРЕАТИВНОСТЬ: <одна метафора>\n"
                    + "РАЗНООБРАЗИЕ:\n"
                    + "1. <название>\n"
                    + "2. <название>\n"
                    + "3. <название>\n"
                    + "4. <название>\n"
                    + "5. <название>\n\n"
                    + "Не добавляй пояснений, заголовков или текста "
                    + "вне указанного формата.";

    // Эталонные нормализованные ответы для части «ТОЧНОСТЬ».
    private static final Map<String, String> EXPECTED_ANSWERS = Map.of(
            "A", "869",
            "B", "32",
            "C", "париж",
            "D", "90",
            "E", "h2o");

    // Запрещённые слова для части «КРЕАТИВНОСТЬ».
    private static final List<String> FORBIDDEN_WORDS =
            List.of("бодрость", "энергия", "утро", "аромат", "напиток");

    // Ширина колонки с метафорой в сравнительной таблице.
    private static final int ANSWER_COLUMN_WIDTH = 30;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Результат разбора одного ответа модели.
    private record ParsedAnswer(String accuracyLine, String metaphor, List<String> names) {
    }

    // Статистика одной температуры по всем прогонам.
    private static final class TemperatureStats {
        final double temperature;
        final int successfulRuns;
        final int totalCorrect;
        final double averageAccuracyPercent;
        final int metaphorMetTotal;
        final int metaphorMaxTotal;
        final double metaphorCompliancePercent;
        final int totalNames;
        final int uniqueNames;
        final double diversityPercent;
        final int uniqueWordCount;
        final int duplicateNames;
        final int twoWordViolations;
        final int withinQuintetRepeats;
        final int formatViolations;

        private TemperatureStats(double temperature, int successfulRuns, int totalCorrect,
                                 double averageAccuracyPercent, int metaphorMetTotal,
                                 int metaphorMaxTotal, double metaphorCompliancePercent,
                                 int totalNames, int uniqueNames, double diversityPercent,
                                 int uniqueWordCount, int duplicateNames,
                                 int twoWordViolations, int withinQuintetRepeats,
                                 int formatViolations) {
            this.temperature = temperature;
            this.successfulRuns = successfulRuns;
            this.totalCorrect = totalCorrect;
            this.averageAccuracyPercent = averageAccuracyPercent;
            this.metaphorMetTotal = metaphorMetTotal;
            this.metaphorMaxTotal = metaphorMaxTotal;
            this.metaphorCompliancePercent = metaphorCompliancePercent;
            this.totalNames = totalNames;
            this.uniqueNames = uniqueNames;
            this.diversityPercent = diversityPercent;
            this.uniqueWordCount = uniqueWordCount;
            this.duplicateNames = duplicateNames;
            this.twoWordViolations = twoWordViolations;
            this.withinQuintetRepeats = withinQuintetRepeats;
            this.formatViolations = formatViolations;
        }

        static TemperatureStats of(double temperature, List<ParsedAnswer> results) {
            int successfulRuns = 0;
            int totalCorrect = 0;
            int metaphorMetTotal = 0;
            int totalNames = 0;
            Set<String> uniqueNames = new HashSet<>();
            Set<String> uniqueWords = new HashSet<>();
            int twoWordViolations = 0;
            int withinQuintetRepeats = 0;
            int formatViolations = 0;

            for (ParsedAnswer parsed : results) {
                if (parsed == null) {
                    // Ошибка запроса не входит в статистику и не является нарушением формата.
                    continue;
                }
                successfulRuns++;

                // Точность: нечитаемая строка «ТОЧНОСТЬ:» даёт 0 из 5.
                totalCorrect += countCorrectAnswers(parsed.accuracyLine());

                // Творческие ограничения: до 4 требований на прогон.
                metaphorMetTotal += metaphorRequirementsMet(parsed.metaphor());

                // Нарушение формата: отсутствует любая из трёх обязательных частей.
                if (parsed.accuracyLine() == null
                        || parsed.metaphor() == null
                        || parsed.names().size() < 5) {
                    formatViolations++;
                }

                // Разнообразие: собираем все извлечённые названия.
                Set<String> wordsInRun = new HashSet<>();
                for (String name : parsed.names()) {
                    totalNames++;
                    String lower = name.toLowerCase(Locale.ROOT);
                    uniqueNames.add(lower);
                    if (countWords(lower) != 2) {
                        twoWordViolations++;
                    }
                    for (String word : lower.split("\\s+")) {
                        if (word.isEmpty()) {
                            continue;
                        }
                        uniqueWords.add(word);
                        // Каждая лишняя встреча слова внутри одной пятёрки — повтор.
                        if (!wordsInRun.add(word)) {
                            withinQuintetRepeats++;
                        }
                    }
                }
            }

            double averageAccuracyPercent = successfulRuns == 0
                    ? 0.0
                    : totalCorrect * 100.0 / (successfulRuns * (double) EXPECTED_ANSWERS.size());
            int metaphorMaxTotal = successfulRuns * 4;
            double metaphorCompliancePercent = metaphorMaxTotal == 0
                    ? 0.0
                    : metaphorMetTotal * 100.0 / metaphorMaxTotal;
            int duplicateNames = totalNames - uniqueNames.size();
            double diversityPercent = totalNames == 0
                    ? 0.0
                    : uniqueNames.size() * 100.0 / totalNames;

            return new TemperatureStats(temperature, successfulRuns, totalCorrect,
                    averageAccuracyPercent, metaphorMetTotal, metaphorMaxTotal,
                    metaphorCompliancePercent, totalNames, uniqueNames.size(),
                    diversityPercent, uniqueWords.size(), duplicateNames,
                    twoWordViolations, withinQuintetRepeats, formatViolations);
        }
    }

    public static void main(String[] args) {
        Config config;
        try {
            config = Config.fromEnv();
        } catch (IllegalStateException e) {
            System.err.println("Ошибка конфигурации: " + e.getMessage());
            return;
        }

        System.out.println("=== День 4. Сравнение температур на комбинированном запросе ===");
        System.out.println("Модель: " + config.model);
        System.out.printf(Locale.ROOT, "Температур: %d, прогонов на каждую: %d, всего запросов: %d%n",
                TEMPERATURES.length, RUNS_PER_TEMPERATURE, TEMPERATURES.length * RUNS_PER_TEMPERATURE);
        System.out.println("Задача:\n" + DEFAULT_TASK);
        System.out.println();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        List<TemperatureStats> statsList = new ArrayList<>();
        // Метафоры по прогонам для каждой температуры (null — ошибка запроса, "" — метафора не найдена).
        List<List<String>> metaphorsByTemperature = new ArrayList<>();

        // Один и тот же комбинированный запрос выполняется на каждой температуре.
        for (double temperature : TEMPERATURES) {
            System.out.println("------------------------------------------------");
            System.out.printf(Locale.ROOT, "TEMPERATURE = %.1f%n", temperature);
            System.out.println("------------------------------------------------");

            List<ParsedAnswer> results = new ArrayList<>();
            List<String> metaphors = new ArrayList<>();

            for (int run = 1; run <= RUNS_PER_TEMPERATURE; run++) {
                String label = String.format(Locale.ROOT,
                        "Ждём ответ модели (t=%.1f, прогон %d/%d)",
                        temperature, run, RUNS_PER_TEMPERATURE);
                try {
                    String answer = sendChatRequest(client, config, DEFAULT_TASK, temperature, label);
                    ParsedAnswer parsed = parseAnswer(answer);
                    results.add(parsed);
                    metaphors.add(parsed.metaphor() == null ? "" : parsed.metaphor());
                    System.out.printf(Locale.ROOT, "  [прогон %d] %s%n", run, answer);
                    printRunScores(parsed);
                } catch (Exception e) {
                    results.add(null);
                    metaphors.add(null);
                    String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    System.out.printf(Locale.ROOT, "  [прогон %d] Ошибка: %s%n", run, message);
                }
            }

            metaphorsByTemperature.add(metaphors);

            TemperatureStats stats = TemperatureStats.of(temperature, results);
            statsList.add(stats);
            printTemperatureMetrics(stats);
        }

        printMetaphorTable(metaphorsByTemperature);
        printSummaryTable(statsList);
        printAutomaticAnalysis(statsList);

        System.out.println("=== Готово. ===");
    }

    // ---------- Разбор ответа модели ----------

    // Разбирает три обязательные части ответа: ТОЧНОСТЬ, КРЕАТИВНОСТЬ, РАЗНООБРАЗИЕ.
    private static ParsedAnswer parseAnswer(String answer) {
        String accuracyLine = null;
        String metaphor = null;
        List<String> names = new ArrayList<>();
        boolean namesSection = false;

        for (String rawLine : answer.split("\\R")) {
            String line = rawLine.trim();
            if (line.startsWith("ТОЧНОСТЬ:")) {
                accuracyLine = line.substring("ТОЧНОСТЬ:".length()).trim();
                namesSection = false;
            } else if (line.startsWith("КРЕАТИВНОСТЬ:")) {
                metaphor = line.substring("КРЕАТИВНОСТЬ:".length()).trim();
                namesSection = false;
            } else if (line.startsWith("РАЗНООБРАЗИЕ")) {
                namesSection = true;
            } else if (namesSection && !line.isEmpty() && names.size() < 5) {
                // Удаляем только числовую нумерацию «1.» — «5.».
                names.add(line.replaceFirst("^[1-5]\\.\\s*", "").trim());
            }
        }
        return new ParsedAnswer(accuracyLine, metaphor, names);
    }

    // Считает правильные ответы A–E в строке «ТОЧНОСТЬ»; сравнение без регистра и лишних пробелов.
    private static int countCorrectAnswers(String accuracyLine) {
        if (accuracyLine == null) {
            return 0;
        }
        int correct = 0;
        for (String part : accuracyLine.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq).trim().toUpperCase(Locale.ROOT);
            String value = normalizeComparisonValue(part.substring(eq + 1));
            if (value.equals(EXPECTED_ANSWERS.get(key))) {
                correct++;
            }
        }
        return correct;
    }

    // Нормализация значения для сравнения: нижний регистр и удаление всех пробелов.
    private static String normalizeComparisonValue(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    // Число выполненных объективных требований к метафоре: 0–4 из 4.
    private static int metaphorRequirementsMet(String metaphor) {
        if (metaphor == null) {
            return 0;
        }
        int met = 0;
        String lower = metaphor.toLowerCase(Locale.ROOT);
        // 1. Метафора существует и не пустая.
        if (!lower.isBlank()) {
            met++;
        }
        // 2. От 6 до 10 слов включительно.
        int words = countWords(lower);
        if (words >= 6 && words <= 10) {
            met++;
        }
        // 3. Без запрещённых слов.
        boolean containsForbidden = FORBIDDEN_WORDS.stream().anyMatch(lower::contains);
        if (!containsForbidden) {
            met++;
        }
        // 4. Записана в одной строке.
        if (!metaphor.contains("\n")) {
            met++;
        }
        return met;
    }

    private static int countWords(String text) {
        String trimmed = text.trim();
        return trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
    }

    // ---------- Вывод результатов ----------

    // Оценки одного прогона.
    private static void printRunScores(ParsedAnswer parsed) {
        if (parsed.accuracyLine() == null) {
            System.out.println(
                    "  Нарушение формата: строка «ТОЧНОСТЬ:» не найдена — все пять ответов считаются неправильными");
        } else {
            int correct = countCorrectAnswers(parsed.accuracyLine());
            System.out.printf(Locale.ROOT, "  Точность: %d из 5 (%.0f%%)%n",
                    correct, correct * 100.0 / EXPECTED_ANSWERS.size());
        }
        int met = metaphorRequirementsMet(parsed.metaphor());
        System.out.printf(Locale.ROOT, "  Креативность: %d из 4 (%.0f%%)%n", met, met * 100.0 / 4.0);
    }

    // Метрики одной температуры.
    private static void printTemperatureMetrics(TemperatureStats stats) {
        System.out.printf(Locale.ROOT, "  → Успешных запросов: %d из %d%n",
                stats.successfulRuns, RUNS_PER_TEMPERATURE);
        System.out.printf(Locale.ROOT, "  → Средняя точность: %.1f%%%n", stats.averageAccuracyPercent);
        System.out.printf(Locale.ROOT, "  → Соблюдение творческих ограничений: %d из %d (%.1f%%)%n",
                stats.metaphorMetTotal, stats.metaphorMaxTotal, stats.metaphorCompliancePercent);
        System.out.printf(Locale.ROOT, "  → Названий извлечено: %d, уникальных: %d (%.1f%%)%n",
                stats.totalNames, stats.uniqueNames, stats.diversityPercent);
        System.out.printf(Locale.ROOT, "  → Уникальных слов: %d, повторов названий: %d%n",
                stats.uniqueWordCount, stats.duplicateNames);
        System.out.printf(Locale.ROOT, "  → Нарушений «два слова»: %d, повторов слов внутри пятёрки: %d%n",
                stats.twoWordViolations, stats.withinQuintetRepeats);
        System.out.printf(Locale.ROOT, "  → Нарушений формата: %d%n%n", stats.formatViolations);
    }

    // Сравнительная таблица метафор по прогонам.
    private static void printMetaphorTable(List<List<String>> metaphorsByTemperature) {
        System.out.println("=============== СРАВНЕНИЕ МЕТАФОР ПО ПРОГОНАМ ===============");

        StringBuilder headerFormat = new StringBuilder("%-6s");
        StringBuilder rowFormat = new StringBuilder("%-6d");
        for (int i = 0; i < TEMPERATURES.length; i++) {
            headerFormat.append(" | %-").append(ANSWER_COLUMN_WIDTH).append("s");
            rowFormat.append(" | %-").append(ANSWER_COLUMN_WIDTH).append("s");
        }

        Object[] headers = new Object[TEMPERATURES.length + 1];
        headers[0] = "Прогон";
        for (int i = 0; i < TEMPERATURES.length; i++) {
            headers[i + 1] = String.format(Locale.ROOT, "t=%.1f", TEMPERATURES[i]);
        }
        System.out.printf(Locale.ROOT, headerFormat + "%n", headers);

        for (int run = 0; run < RUNS_PER_TEMPERATURE; run++) {
            Object[] cells = new Object[TEMPERATURES.length + 1];
            cells[0] = run + 1;
            for (int i = 0; i < TEMPERATURES.length; i++) {
                List<String> metaphors = metaphorsByTemperature.get(i);
                cells[i + 1] = run < metaphors.size() ? displayMetaphor(metaphors.get(run)) : "—";
            }
            System.out.printf(Locale.ROOT, rowFormat + "%n", cells);
        }
        System.out.println("=============================================================");

        // Полные тексты — для ручной оценки образности и оригинальности.
        System.out.println("Полные тексты метафор:");
        for (int run = 0; run < RUNS_PER_TEMPERATURE; run++) {
            for (int i = 0; i < TEMPERATURES.length; i++) {
                List<String> metaphors = metaphorsByTemperature.get(i);
                String text = run < metaphors.size() ? metaphors.get(run) : null;
                String cell = text == null ? "(ошибка запроса)"
                        : text.isEmpty() ? "(метафора не найдена)" : text;
                System.out.printf(Locale.ROOT, "  Прогон %d, t=%.1f: %s%n", run + 1, TEMPERATURES[i], cell);
            }
        }
        System.out.println();
    }

    // Сокращает только отображение метафоры: null — ошибка, пустая строка — не найдена.
    private static String displayMetaphor(String metaphor) {
        if (metaphor == null) {
            return "<ошибка>";
        }
        if (metaphor.isEmpty()) {
            return "—";
        }
        String singleLine = metaphor.replace("\r", " ").replace("\n", " ").trim();
        if (singleLine.length() > ANSWER_COLUMN_WIDTH) {
            return singleLine.substring(0, ANSWER_COLUMN_WIDTH - 3) + "...";
        }
        return singleLine;
    }

    // ---------- Сводная таблица ----------

    private static void printSummaryTable(List<TemperatureStats> statsList) {
        System.out.println("============================================ ИТОГОВАЯ ТАБЛИЦА ============================================");
        System.out.printf(Locale.ROOT,
                "%-11s | %-16s | %-33s | %-19s | %-14s | %-12s | %-15s | %-8s | %-17s%n",
                "Температура", "Средняя точность", "Соблюдение творческих ограничений",
                "Уникальных названий", "Всего названий", "Разнообразие", "Уникальных слов",
                "Повторов", "Нарушений формата");
        System.out.println(
                "------------+------------------+-----------------------------------+---------------------"
                        + "+----------------+--------------+-----------------+----------+------------------");
        for (TemperatureStats s : statsList) {
            System.out.printf(Locale.ROOT,
                    "%-11.1f | %-16s | %-33s | %-19d | %-14d | %-12s | %-15d | %-8d | %-17d%n",
                    s.temperature,
                    String.format(Locale.ROOT, "%.1f%%", s.averageAccuracyPercent),
                    s.metaphorMetTotal + " из " + s.metaphorMaxTotal + " ("
                            + String.format(Locale.ROOT, "%.1f%%", s.metaphorCompliancePercent) + ")",
                    s.uniqueNames,
                    s.totalNames,
                    String.format(Locale.ROOT, "%.1f%%", s.diversityPercent),
                    s.uniqueWordCount,
                    s.duplicateNames,
                    s.formatViolations);
        }
        System.out.println(
                "==========================================================================================================");
    }

    // ---------- Автоматический анализ по фактическим метрикам ----------

    private static void printAutomaticAnalysis(List<TemperatureStats> statsList) {
        System.out.println("==================== АВТОМАТИЧЕСКИЙ АНАЛИЗ ====================");

        double[] temperatures = new double[statsList.size()];
        for (int i = 0; i < statsList.size(); i++) {
            temperatures[i] = statsList.get(i).temperature;
        }

        printMetricWinner("Средняя точность",
                statsList.stream().mapToDouble(s -> s.averageAccuracyPercent).toArray(),
                temperatures, "%.1f%%");
        printMetricWinner("Соблюдение творческих ограничений",
                statsList.stream().mapToDouble(s -> s.metaphorCompliancePercent).toArray(),
                temperatures, "%.1f%%");
        printMetricWinner("Уникальные названия",
                statsList.stream().mapToDouble(s -> s.uniqueNames).toArray(),
                temperatures, "%.0f");
        printMetricWinner("Уникальные слова",
                statsList.stream().mapToDouble(s -> s.uniqueWordCount).toArray(),
                temperatures, "%.0f");
        printMetricWinner("Нарушения формата",
                statsList.stream().mapToDouble(s -> s.formatViolations).toArray(),
                temperatures, "%.0f");

        System.out.println("Образность и оригинальность метафор оцениваются пользователем вручную по напечатанным примерам.");
        System.out.println();
        System.out.println("Точность оценивается по пяти заданиям с эталонными ответами.");
        System.out.println("Креативность оценивается по образности метафор и соблюдению ограничений.");
        System.out.println("Разнообразие оценивается по уникальности названий и использованных слов.");
        System.out.println();
    }

    // Ищет максимум метрики; при совпадении значений прямо сообщает об отсутствии различий.
    private static void printMetricWinner(String metricName, double[] values,
                                          double[] temperatures, String valueFormat) {
        double max = Double.NEGATIVE_INFINITY;
        for (double value : values) {
            if (value > max) {
                max = value;
            }
        }

        List<String> winners = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(values[i] - max) < 1e-9) {
                winners.add(String.format(Locale.ROOT, "t=%.1f", temperatures[i]));
            }
        }

        String maxValue = String.format(Locale.ROOT, valueFormat, max);
        if (winners.size() == 1) {
            System.out.printf(Locale.ROOT, "  • %s: максимум у %s (%s)%n",
                    metricName, winners.get(0), maxValue);
        } else {
            System.out.printf(Locale.ROOT, "  • %s: различия по этой метрике не обнаружены — %s (по %s у всех)%n",
                    metricName, String.join(", ", winners), maxValue);
        }
    }

    // ---------- HTTP-запрос к LLM ----------

    private static String sendChatRequest(HttpClient client,
                                          Config config,
                                          String userPrompt,
                                          double temperature,
                                          String loaderLabel) throws IOException, InterruptedException {
        String body = buildRequestBody(config.model, userPrompt, temperature);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.apiUrl))
                .timeout(Duration.ofSeconds(300))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        // Спиннер работает в отдельном потоке, пока идёт запрос.
        Spinner spinner = new Spinner(loaderLabel);
        Thread spinnerThread = new Thread(spinner);
        spinnerThread.setDaemon(true);
        spinnerThread.start();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new IOException("Превышено время ожидания ответа модели", e);
        } finally {
            // Останавливаем спиннер, прерываем поток, дожидаемся его (с таймаутом, чтобы не зависнуть)
            // и только затем очищаем строку: спиннер и результат никогда не печатаются одновременно.
            spinner.stop();
            spinnerThread.interrupt();
            try {
                spinnerThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            spinner.clearLine();
        }

        if (response.statusCode() != 200) {
            String responseBody = response.body() == null ? "" : response.body();
            throw new IOException("HTTP " + response.statusCode() + ": " + responseBody);
        }

        return extractContent(response.body());
    }

    private static String buildRequestBody(String model, String userPrompt, double temperature) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("temperature", temperature);

        ArrayNode messages = root.putArray("messages");
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", userPrompt);

        // Блок thinking.disabled намеренно не добавляем:
        // некоторые модели работают только с включённым reasoning и иначе возвращают 400.

        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сформировать тело запроса", e);
        }
    }

    // Достаём итоговый текст ответа; при необходимости учитываем reasoning_content.
    private static String extractContent(String responseBody) throws IOException {
        JsonNode root = MAPPER.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IOException("В ответе модели нет поля choices");
        }

        JsonNode message = choices.get(0).path("message");
        JsonNode content = message.path("content");

        if (content.isTextual() && !content.asText().isBlank()) {
            return content.asText().trim();
        }

        // Некоторые модели кладут ответ в reasoning_content.
        JsonNode reasoning = message.path("reasoning_content");
        if (reasoning.isTextual() && !reasoning.asText().isBlank()) {
            return reasoning.asText().trim();
        }

        throw new IOException("Пустой ответ модели");
    }

    // ---------- Конфигурация из переменных окружения ----------

    private static final class Config {
        final String apiKey;
        final String apiUrl;
        final String model;

        private Config(String apiKey, String apiUrl, String model) {
            this.apiKey = apiKey;
            this.apiUrl = apiUrl;
            this.model = model;
        }

        static Config fromEnv() {
            String apiKey = System.getenv("LLM_API_KEY");
            String apiUrl = System.getenv("LLM_API_URL");
            String model = System.getenv("LLM_MODEL");

            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("Не задана переменная окружения LLM_API_KEY");
            }
            if (apiUrl == null || apiUrl.isBlank()) {
                throw new IllegalStateException("Не задана переменная окружения LLM_API_URL");
            }
            if (model == null || model.isBlank()) {
                throw new IllegalStateException("Не задана переменная окружения LLM_MODEL");
            }
            return new Config(apiKey, apiUrl, model);
        }
    }

    // ---------- Спиннер-загрузчик ----------

    private static final class Spinner implements Runnable {
        private final String label;
        private volatile boolean running = true;

        Spinner(String label) {
            this.label = label;
        }

        void stop() {
            running = false;
        }

        @Override
        public void run() {
            char[] frames = {'|', '/', '-', '\\'};
            int i = 0;
            long start = System.currentTimeMillis();
            while (running) {
                long seconds = (System.currentTimeMillis() - start) / 1000;
                System.out.printf(Locale.ROOT, "\r%s %c  %d с ", label, frames[i % frames.length], seconds);
                System.out.flush();
                i++;
                try {
                    Thread.sleep(120);
                } catch (InterruptedException e) {
                    break;
                }
            }
            // Строку очищает основной поток после join — см. clearLine().
        }

        // Полностью очищает строку спиннера. Вызывается только после завершения потока спиннера.
        void clearLine() {
            System.out.print("\r" + " ".repeat(label.length() + 20) + "\r");
            System.out.flush();
        }
    }
}
