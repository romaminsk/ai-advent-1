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
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class Day5 {

    // Три модели разной силы: слабая, средняя и сильная.
    // Идентификаторы в формате провайдера (строчными буквами, дефисы).
    private static final String[] MODELS = {"glm-5.3-flash", "glm-5.2", "qwen3.8-max"};

    // Метки силы моделей (индексы совпадают с MODELS).
    private static final String[] MODEL_LABELS = {"слабая", "средняя", "сильная"};

    // Цены за 1K токенов (in/out) в долларах. Значения — ЗАГЛУШКИ для примера:
    // реальные цены нужно взять из актуального прайса провайдера.
    // glm-5.3-flash — бесплатная/локальная модель, стоимость всегда 0.
    // ВАЖНО: цена именно за 1000 токенов, формула делит токены на 1000.
    private static final double FLASH_IN_PER_1K = 0.0;
    private static final double FLASH_OUT_PER_1K = 0.0;
    private static final double GLM_IN_PER_1K = 0.0005;
    private static final double GLM_OUT_PER_1K = 0.0015;
    private static final double QWEN_IN_PER_1K = 0.002;
    private static final double QWEN_OUT_PER_1K = 0.008;

    private static final Map<String, double[]> PRICES_PER_1K = Map.of(
            "glm-5.3-flash", new double[]{FLASH_IN_PER_1K, FLASH_OUT_PER_1K},
            "glm-5.2", new double[]{GLM_IN_PER_1K, GLM_OUT_PER_1K},
            "qwen3.8-max", new double[]{QWEN_IN_PER_1K, QWEN_OUT_PER_1K});

    // Единый запрос для всех трёх моделей: неочевидные задачи с ловушками,
    // где слабая модель почти гарантированно ошибается, сильная — нет.
    private static final String COMMON_TASK = """
            Реши ПЯТЬ задач. Отвечай строго в формате, без пояснений вне формата.

            1. ЛОГИКА-ЛОВУШКА
            У Марии есть брат. У брата Марии столько же братьев, сколько сестёр.
            У Марии вдвое меньше сестёр, чем братьев. Сколько в семье братьев и
            сколько сестёр? Дай числа и краткое обоснование.

            2. ФАКТ-ЛОВУШКИ (если утверждение ложно — прямо укажи)
            A) Столица Австралии?
            B) Сколько будет 0.1 + 0.2 в арифметике double? Ответь точным значением.
            C) Какое число идёт следующим: 1, 11, 21, 1211, 111221, ...?
            D) Сколько букв «р» в слове «программирование»?

            3. ТОЧНЫЙ ПОДСЧЁТ
            Часы показывают 15:15. Какой угол (в градусах) между часовой и
            минутной стрелками? Покажи вычисление.

            4. РЕДКОЕ ЗНАНИЕ
            Что выведет Java-код: System.out.println(0.1 + 0.2 == 0.3); и почему?

            5. КОД С ПОДВОХОМ
            Напиши Java-функцию int[] twoSum(int[] nums, int target), которая
            возвращает индексы двух чисел, дающих target. Сложность O(n).
            Код должен компилироваться.

            Ответь строго:
            ЛОГИКА: братьев=<n>; сестёр=<n>; обоснование=<...>
            ФАКТ: A=<...>; B=<...>; C=<...>; D=<...>
            УГОЛ: <вычисление> => <градусы>
            JAVA: <ответ true/false> — <объяснение>
            КОД:
            <java-код одним блоком>
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Результат разбора одного ответа модели: true означает зачтённый пункт.
    // Проваленные пункты перечисляются для наглядного сравнения моделей.
    private record ParsedAnswer(boolean logicOk, boolean factAOk, boolean factBOk,
                                boolean factCOk, boolean factDOk, boolean angleOk,
                                boolean javaOk, boolean codeOk,
                                int formatViolations) {
    }

    // Статистика одной модели: время, токены (в т.ч. reasoning), стоимость и баллы.
    private static final class ModelStats {
        final String model;
        final String label;
        final long elapsedMillis;
        final long promptTokens;
        final long completionTokens;
        final long reasoningTokens;
        final long totalTokens;
        final double cost;
        final ParsedAnswer parsed;
        final int qualityScore;
        final String answer;

        private ModelStats(String model, String label, long elapsedMillis,
                           long promptTokens, long completionTokens, long reasoningTokens,
                           long totalTokens, double cost, ParsedAnswer parsed,
                           int qualityScore, String answer) {
            this.model = model;
            this.label = label;
            this.elapsedMillis = elapsedMillis;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.reasoningTokens = reasoningTokens;
            this.totalTokens = totalTokens;
            this.cost = cost;
            this.parsed = parsed;
            this.qualityScore = qualityScore;
            this.answer = answer;
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

        System.out.println("=== День 5. Версии моделей: одна задача — три модели ===");
        System.out.println("Модели: " + String.join(", ", MODELS)
                + " (метки: " + String.join(", ", MODEL_LABELS) + ")");
        System.out.println("Задача:\n" + COMMON_TASK);
        System.out.println();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        List<ModelStats> statsList = new ArrayList<>();

        // Один и тот же запрос выполняется на каждой модели.
        for (int i = 0; i < MODELS.length; i++) {
            String model = MODELS[i];
            String label = MODEL_LABELS[i];

            System.out.println("------------------------------------------------");
            System.out.printf(Locale.ROOT, "МОДЕЛЬ: %s (%s)%n", model, label);
            System.out.println("------------------------------------------------");

            String loaderLabel = "Ждём ответ модели (" + model + ")";
            try {
                long start = System.nanoTime();
                Response response = sendChatRequest(client, config, model, COMMON_TASK, loaderLabel);
                long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

                double cost = computeCost(model, response.promptTokens, response.completionTokens);
                ParsedAnswer parsed = parseAnswer(response.content);
                int qualityScore = countQualityScore(parsed);

                ModelStats stats = new ModelStats(model, label, elapsedMillis,
                        response.promptTokens, response.completionTokens,
                        response.reasoningTokens, response.totalTokens,
                        cost, parsed, qualityScore, response.content);
                statsList.add(stats);

                System.out.println("Ответ модели:");
                System.out.println(response.content);
                System.out.println();
                printModelMetrics(stats);
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                System.out.printf(Locale.ROOT, "Ошибка запроса к модели %s: %s%n%n", model, message);
            }
        }

        printSummaryTable(statsList);
        printAutomaticAnalysis(statsList);
        printConclusion(statsList);
        System.out.println("=== Готово. ===");
    }

    // ---------- Разбор ответа модели ----------

    // Разбирает ответ по пяти частям и сверяет с эталоном ТОЧНО (без дробных критериев).
    private static ParsedAnswer parseAnswer(String answer) {
        int formatViolations = 0;

        String logicLine = null;
        String factLine = null;
        String angleLine = null;
        String javaLine = null;
        for (String rawLine : answer.split("\\R")) {
            String line = rawLine.trim();
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.startsWith("ЛОГИКА") && logicLine == null) {
                logicLine = line;
            } else if (upper.startsWith("ФАКТ") && factLine == null) {
                factLine = line;
            } else if (upper.startsWith("УГОЛ") && angleLine == null) {
                angleLine = line;
            } else if (upper.startsWith("JAVA") && javaLine == null) {
                javaLine = line;
            }
        }
        if (logicLine == null) {
            formatViolations++;
        }
        if (factLine == null) {
            formatViolations++;
        }
        if (angleLine == null) {
            formatViolations++;
        }
        if (javaLine == null) {
            formatViolations++;
        }

        // ЛОГИКА: эталон братьев=4, сестёр=3 (точное совпадение чисел).
        // Ловушка: у брата 3 брата (4-1) и 3 сестры; у Марии 3 сестры и 4 брата.
        boolean factLineHasKeyValues = factLine != null;
        boolean logicOk = extractNumber(logicLine, "братьев") == 4
                && extractNumber(logicLine, "сестёр") == 3;

        // ФАКТ: A=Канберра; B=0.30000000000000004; C=312211; D=3 буквы «р».
        boolean factAOk = factLineHasKeyValues && matchFactValue(factLine, "A",
                List.of("канберра", "canberra"));
        // Ловушка: точное значение 0.1+0.2 в double — 0.30000000000000004 (не 0.3).
        boolean factBOk = factLineHasKeyValues && matchFactValue(factLine, "B",
                List.of("0.30000000000000004", "0.30000000000000004"));
        boolean factCOk = factLineHasKeyValues && matchFactValue(factLine, "C",
                List.of("312211"));
        boolean factDOk = factLineHasKeyValues && matchFactValue(factLine, "D",
                List.of("3"));

        // УГОЛ: 15:15 => 7.5 градуса (не 0!). Берём последнее число строки.
        boolean angleOk = angleLine != null && extractLastNumber(angleLine) == 7.5;

        // JAVA: ответ false (из-за погрешности double).
        boolean javaOk = javaLine != null
                && normalizeComparisonValue(javaLine).contains("false")
                && !normalizeComparisonValue(javaLine).contains("true");

        // КОД: twoSum + HashMap/Map (признак O(n)).
        String codeSection = extractSection(answer, "КОД", null);
        boolean codeOk = codeSection != null
                && codeSection.contains("twoSum")
                && (codeSection.contains("HashMap") || codeSection.contains("Map"));
        if (codeSection == null) {
            formatViolations++;
        }

        return new ParsedAnswer(logicOk, factAOk, factBOk, factCOk, factDOk,
                angleOk, javaOk, codeOk, formatViolations);
    }

    // Извлекает целое число из фрагмента «ключ=<число>» в строке; -1 если не найдено.
    private static int extractNumber(String line, String key) {
        if (line == null) {
            return -1;
        }
        String lower = normalizeComparisonValue(line);
        int idx = lower.indexOf(key.toLowerCase(Locale.ROOT) + "=");
        if (idx < 0) {
            return -1;
        }
        int start = idx + key.length() + 1;
        StringBuilder digits = new StringBuilder();
        for (int i = start; i < lower.length() && (Character.isDigit(lower.charAt(i))
                || (lower.charAt(i) == '-' && digits.isEmpty())); i++) {
            digits.append(lower.charAt(i));
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // Извлекает последнее число в строке с учётом десятичной части.
    private static double extractLastNumber(String line) {
        if (line == null) {
            return Double.NaN;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(\\d+(?:[.,]\\d+)?)\\s*(?:градуса|градусов|°|$)")
                .matcher(normalizeComparisonValue(line));
        double last = Double.NaN;
        while (matcher.find()) {
            last = Double.parseDouble(matcher.group(1).replace(',', '.'));
        }
        return last;
    }

    // Проверка пункта ФАКТ: точное совпадение значения с одним из эталонов
    // (нормализация регистра и пробелов).
    private static boolean matchFactValue(String factLine, String key, List<String> expected) {
        String normalized = normalizeComparisonValue(factLine);
        int idx = normalized.indexOf(key.toLowerCase(Locale.ROOT) + "=");
        if (idx < 0) {
            return false;
        }
        int start = idx + key.length() + 1;
        int end = normalized.indexOf(';', start);
        if (end < 0) {
            end = normalized.length();
        }
        String value = normalized.substring(start, end);
        for (String candidate : expected) {
            if (value.equals(normalizeComparisonValue(candidate))) {
                return true;
            }
        }
        return false;
    }

    // Извлекает секцию ответа от заголовка section до конца (или следующего явного блока).
    private static String extractSection(String answer, String section, String nextSection) {
        String upper = answer.toUpperCase(Locale.ROOT);
        int start = upper.indexOf(section.toUpperCase(Locale.ROOT));
        if (start < 0) {
            return null;
        }
        start += section.length();
        int end = answer.length();
        if (nextSection != null) {
            int next = upper.indexOf(nextSection.toUpperCase(Locale.ROOT), start);
            if (next >= 0) {
                end = next;
            }
        }
        return answer.substring(start, end);
    }

    // Список проваленных пунктов конкретной модели — для наглядного сравнения.
    private static List<String> failedItems(ParsedAnswer parsed) {
        List<String> failed = new ArrayList<>();
        if (!parsed.logicOk()) {
            failed.add("ЛОГИКА");
        }
        if (!parsed.factAOk()) {
            failed.add("ФАКТ-A");
        }
        if (!parsed.factBOk()) {
            failed.add("ФАКТ-B");
        }
        if (!parsed.factCOk()) {
            failed.add("ФАКТ-C");
        }
        if (!parsed.factDOk()) {
            failed.add("ФАКТ-D");
        }
        if (!parsed.angleOk()) {
            failed.add("УГОЛ");
        }
        if (!parsed.javaOk()) {
            failed.add("JAVA");
        }
        if (!parsed.codeOk()) {
            failed.add("КОД");
        }
        return failed;
    }

    // Качественное описание модели, вычисляемое по её фактическим результатам;
    // попадает в итоговую таблицу отдельной колонкой.
    private static String qualitativeTrait(ModelStats s) {
        List<String> marked = new ArrayList<>();
        if (s.elapsedMillis <= 15_000) {
            marked.add("быстрая");
        } else if (s.elapsedMillis >= 60_000) {
            marked.add("медленная");
        }
        if (s.reasoningTokens > 0) {
            marked.add("reasoning-модель");
        }
        if (s.qualityScore <= 2) {
            marked.add("ловушки не видит");
        } else if (s.qualityScore >= 4) {
            marked.add("ловушки распознаёт");
        } else {
            marked.add("ловушки видит частично");
        }
        if (s.parsed.codeOk()) {
            marked.add("код с O(n) написала");
        } else {
            marked.add("код с O(n) промах");
        }
        if (s.cost == 0.0) {
            marked.add("бесплатная");
        } else if (s.cost >= 0.005) {
            marked.add("дорогая");
        } else {
            marked.add("дешёвая");
        }
        return String.join("; ", marked);
    }

    // Итоговый балл качества: 0–5, без дробных критериев.
    // 1) ЛОГИКА (братьев=4, сестёр=3)
    // 2) ФАКТ-A (Канберра) + ФАКТ-C (312211) + ФАКТ-D (3 буквы «р»)
    // 3) ФАКТ-B (0.30000000000000004) + JAVA (false) — двойная проверка знания double
    // 4) УГОЛ (7.5, а не 0)
    // 5) КОД (twoSum + HashMap => O(n))
    private static int countQualityScore(ParsedAnswer parsed) {
        int score = 0;
        if (parsed.logicOk()) {
            score++;
        }
        if (parsed.factAOk() && parsed.factCOk() && parsed.factDOk()) {
            score++;
        }
        if (parsed.factBOk() && parsed.javaOk()) {
            score++;
        }
        if (parsed.angleOk()) {
            score++;
        }
        if (parsed.codeOk()) {
            score++;
        }
        return Math.min(score, 5);
    }

    // Стоимость запроса в долларах:
    // cost = prompt/1000*inPer1K + completion/1000*outPer1K.
    private static double computeCost(String model, long promptTokens, long completionTokens) {
        double[] prices = PRICES_PER_1K.get(model);
        if (prices == null) {
            return 0.0;
        }
        return promptTokens / 1000.0 * prices[0] + completionTokens / 1000.0 * prices[1];
    }

    // Нормализация значения для сравнения: нижний регистр и удаление всех пробелов.
    private static String normalizeComparisonValue(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    // ---------- Вывод результатов ----------

    // Метрики одной модели, включая разделение reasoning-токенов.
    private static void printModelMetrics(ModelStats stats) {
        System.out.printf(Locale.ROOT, "  Время ответа: %d мс%n", stats.elapsedMillis);
        System.out.printf(Locale.ROOT, "  Токены: prompt=%d, completion=%d, reasoning=%d, total=%d%n",
                stats.promptTokens, stats.completionTokens, stats.reasoningTokens, stats.totalTokens);
        System.out.printf(Locale.ROOT, "  Стоимость: $%.6f%n", stats.cost);
        System.out.printf(Locale.ROOT, "  Баллы качества: %d из 5%n", stats.qualityScore);

        List<String> failed = failedItems(stats.parsed);
        if (failed.isEmpty()) {
            System.out.println("  Проваленные пункты: нет — все критерии зачтены");
        } else {
            System.out.println("  Проваленные пункты: " + String.join(", ", failed));
        }
        System.out.printf(Locale.ROOT, "  Нарушений формата: %d%n%n", stats.parsed.formatViolations());
    }

    // ---------- Сводная таблица ----------

    private static void printSummaryTable(List<ModelStats> statsList) {
        System.out.println("=============================================================================================== ИТОГОВАЯ ТАБЛИЦА ===============================================================================================");
        System.out.printf(Locale.ROOT,
                "%-13s | %-8s | %-10s | %-11s | %-15s | %-15s | %-10s | %-8s | %-22s | %-13s | %-52s%n",
                "Модель", "Сила", "Время(мс)", "Токены(in)",
                "completion", "reasoning", "Стоимость", "Баллы", "Проваленные пункты",
                "Наруш.формата", "Особенности модели");
        System.out.println(
                "--------------+---------+------------+-------------+-----------------+-----------------+-----------+--------+------------------------+--------------+------------------------------------------------------");
        for (ModelStats s : statsList) {
            List<String> failed = failedItems(s.parsed);
            String failedText = failed.isEmpty() ? "—" : String.join(",", failed);
            if (failedText.length() > 22) {
                failedText = failedText.substring(0, 19) + "...";
            }
            System.out.printf(Locale.ROOT,
                    "%-13s | %-8s | %-10d | %-11d | %-15d | %-15d | %-10s | %-8d | %-22s | %-13d | %-52s%n",
                    s.model, s.label, s.elapsedMillis, s.promptTokens,
                    s.completionTokens, s.reasoningTokens,
                    String.format(Locale.ROOT, "$%.6f", s.cost), s.qualityScore,
                    failedText, s.parsed.formatViolations(), qualitativeTrait(s));
        }
        System.out.println(
                "================================================================================================================================================================================================================");
        System.out.println("Пояснение: для reasoning-моделей (qwen3.8-max) высокая стоимость");
        System.out.println("определяется скрытыми reasoning-токенами, а не объёмом полезного ответа.");
        System.out.println();
        // Качественные различия — расшифровка колонки «Особенности модели» по каждой модели.
        System.out.println("Представление различий между моделями (по фактическим результатам):");
        for (ModelStats s : statsList) {
            System.out.printf(Locale.ROOT, "  • %s (%s): особенности: %s%n",
                    s.model, s.label, qualitativeTrait(s));
            System.out.printf(Locale.ROOT, "    время=%d мс; токены in/out/reasoning=%d/%d/%d; стоимость=$%.6f; баллы=%d из 5%n",
                    s.elapsedMillis, s.promptTokens, s.completionTokens,
                    s.reasoningTokens, s.cost, s.qualityScore);
        }
        System.out.println();
    }

    // ---------- Автоматический анализ по фактическим метрикам ----------

    private static void printAutomaticAnalysis(List<ModelStats> statsList) {
        System.out.println("==================== АВТОМАТИЧЕСКИЙ АНАЛИЗ ====================");

        String[] labels = statsList.stream().map(s -> s.model).toArray(String[]::new);

        printMetricWinner("Качество (баллы 0–5)",
                statsList.stream().mapToDouble(s -> s.qualityScore).toArray(), labels, "%.0f");
        printMetricWinner("Скорость (меньше время — лучше)",
                statsList.stream().mapToDouble(s -> -s.elapsedMillis).toArray(), labels, "%.0f мс");
        printMetricWinner("Дешевизна (меньше стоимость — лучше)",
                statsList.stream().mapToDouble(s -> -s.cost).toArray(), labels, "$%.6f");
        printMetricWinner("Экономность (меньше total токенов — лучше)",
                statsList.stream().mapToDouble(s -> -s.totalTokens).toArray(), labels, "%.0f");

        System.out.println();
    }

    // Ищет максимум метрики; при совпадении значений прямо сообщает об отсутствии различий.
    private static void printMetricWinner(String metricName, double[] values,
                                          String[] labels, String valueFormat) {
        double max = Double.NEGATIVE_INFINITY;
        for (double value : values) {
            if (value > max) {
                max = value;
            }
        }

        List<String> winners = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(values[i] - max) < 1e-9) {
                winners.add(labels[i]);
            }
        }

        String maxValue = String.format(Locale.ROOT, valueFormat, max);
        if (winners.size() == 1) {
            System.out.printf(Locale.ROOT, "  • %s: лучшая — %s%n", metricName, winners.get(0));
        } else {
            System.out.printf(Locale.ROOT, "  • %s: различия по этой метрике не обнаружены — %s%n",
                    metricName, String.join(", ", winners));
        }
    }

    // ---------- Текстовый вывод о различиях моделей и ссылки ----------

    private static void printConclusion(List<ModelStats> statsList) {
        System.out.println("=========== ВЫВОД ===========");
        if (statsList.isEmpty()) {
            System.out.println("Все запросы завершились ошибкой — сравнение невозможно.");
            return;
        }

        ModelStats bestQuality = bestBy(statsList, s -> s.qualityScore);
        ModelStats fastest = bestBy(statsList, s -> -s.elapsedMillis);
        ModelStats cheapest = bestBy(statsList, s -> -s.cost);

        System.out.printf(Locale.ROOT,
                "  • Лучшая по качеству: %s (%d из 5)%n",
                bestQuality.model, bestQuality.qualityScore);
        System.out.printf(Locale.ROOT,
                "  • Самая быстрая: %s (%d мс)%n", fastest.model, fastest.elapsedMillis);
        System.out.printf(Locale.ROOT,
                "  • Самая дешёвая: %s ($%.6f)%n", cheapest.model, cheapest.cost);

        System.out.println();
        System.out.println("Различия между моделями на неочевидных задачах:");
        System.out.println("  • Слабая модель часто не видит ловушки: путает Сидней и Канберру,");
        System.out.println("    округляет 0.1+0.2 до 0.3, отвечают 0 на вопрос про угол 15:15,");
        System.out.println("    промахивается в логической задаче про братьев и сестёр.");
        System.out.println("  • Средняя модель точнее в фактах и подсчёте угла, но может");
        System.out.println("    промахнуться в нестандартных задачах.");
        System.out.println("  • Сильная модель решает и редкие пункты, и код с O(n), но тратит");
        System.out.println("    больше reasoning-токенов, времени и денег.");
        System.out.println();
        System.out.println("Полезные ссылки для самостоятельного сравнения моделей:");
        System.out.println("  https://huggingface.co/models");
        System.out.println("  https://huggingface.co/spaces/open-llm-leaderboard/open_llm_leaderboard");
        System.out.println();
    }

    // Модель с максимальным значением метрики.
    private static ModelStats bestBy(List<ModelStats> statsList,
                                     java.util.function.ToDoubleFunction<ModelStats> metric) {
        ModelStats best = statsList.get(0);
        for (ModelStats s : statsList) {
            if (metric.applyAsDouble(s) > metric.applyAsDouble(best)) {
                best = s;
            }
        }
        return best;
    }

    // ---------- HTTP-запрос к LLM ----------

    // Ответ модели вместе с токенами из usage, reasoning-токены выделяются отдельно.
    private record Response(String content, long promptTokens, long completionTokens,
                            long reasoningTokens, long totalTokens) {
    }

    private static Response sendChatRequest(HttpClient client,
                                            Config config,
                                            String model,
                                            String userPrompt,
                                            String loaderLabel) throws IOException, InterruptedException {
        String body = buildRequestBody(model, userPrompt);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.apiUrl))
                .timeout(Duration.ofSeconds(600))
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

        return parseResponse(response.body());
    }

    private static String buildRequestBody(String model, String userPrompt) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        // Ограничение генерации: без него reasoning-модели могут «думать» слишком долго.
        // Значение выбрано с запасом: reasoning-токены расходуются из этого лимита.
        root.put("max_tokens", 4096);

        ArrayNode messages = root.putArray("messages");
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", userPrompt);

        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сформировать тело запроса", e);
        }
    }

    // Разбирает тело ответа: content/reasoning_content и usage
    // (включая reasoning_tokens из completion_tokens_details).
    private static Response parseResponse(String responseBody) throws IOException {
        JsonNode root = MAPPER.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IOException("В ответе модели нет поля choices");
        }

        JsonNode message = choices.get(0).path("message");
        JsonNode content = message.path("content");

        String text;
        if (content.isTextual() && !content.asText().isBlank()) {
            text = content.asText().trim();
        } else {
            // Некоторые модели кладут ответ в reasoning_content.
            JsonNode reasoning = message.path("reasoning_content");
            if (reasoning.isTextual() && !reasoning.asText().isBlank()) {
                text = reasoning.asText().trim();
            } else {
                throw new IOException("Пустой ответ модели");
            }
        }

        JsonNode usage = root.path("usage");
        // Reasoning-токены: отдельное поле или вложенная детализация completion.
        long reasoningTokens = usage.path("reasoning_tokens").asLong(0);
        if (reasoningTokens == 0) {
            reasoningTokens = usage.path("completion_tokens_details")
                    .path("reasoning_tokens").asLong(0);
        }
        return new Response(text,
                usage.path("prompt_tokens").asLong(0),
                usage.path("completion_tokens").asLong(0),
                reasoningTokens,
                usage.path("total_tokens").asLong(0));
    }

    // ---------- Конфигурация из переменных окружения ----------

    private static final class Config {
        final String apiKey;
        final String apiUrl;

        private Config(String apiKey, String apiUrl) {
            this.apiKey = apiKey;
            this.apiUrl = apiUrl;
        }

        static Config fromEnv() {
            String apiKey = System.getenv("LLM_API_KEY");
            String apiUrl = System.getenv("LLM_API_URL");

            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("Не задана переменная окружения LLM_API_KEY");
            }
            if (apiUrl == null || apiUrl.isBlank()) {
                throw new IllegalStateException("Не задана переменная окружения LLM_API_URL");
            }
            return new Config(apiKey, apiUrl);
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
