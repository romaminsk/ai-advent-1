# ai-advent-1

Минимальное консольное Java-приложение, которое отправляет текстовый запрос в LLM через OpenAI-совместимый API и выводит ответ модели в консоль.

Стек: Java 17+, Maven, `java.net.http.HttpClient`, Jackson. Без Spring Boot и лишних библиотек.

## Переменные окружения

| Переменная | Назначение |
|---|---|
| `LLM_API_KEY` | API-ключ провайдера |
| `LLM_API_URL` | Полный URL chat/completions endpoint |
| `LLM_MODEL` | Идентификатор модели |

Значения берутся буквально из переменных — приложение ничего не подставляет само.

## Настройка

```bash
export LLM_API_KEY="ваш-ключ"
export LLM_API_URL="https://api.openai.com/v1/chat/completions"
export LLM_MODEL="gpt-4o-mini"
```

Можно сложить в файл `.env` (он в `.gitignore`) и загрузить, например, через `source .env`.

## Сборка

```bash
mvn package
```

## Запуск

```bash
# со стандартным промптом ("Назови три преимущества языка Java")
mvn -q exec:java

# со своим промптом
mvn -q exec:java -Dexec.args="Расскажи анекдот про программиста"
```

Или через скомпилированный jar:

```bash
mvn package
java -cp target/ai-advent-1-1.0-SNAPSHOT.jar:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout) com.example.Main "Ваш промпт"
```

## Примечания

- Ключ не хранится в исходном коде, читается только из окружения.
- Приложение проверяет HTTP-статус и наличие `choices[0].message.content` в ответе.
