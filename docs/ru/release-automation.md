# Автоматизация релиза

Откройте **Actions → Prepare and merge release → Run workflow**, выберите `main`
и задайте новую версию, например `0.1.2` (без `v`). Запуск разрешает создание
релизного PR, автоматический squash merge после полного CI и создание тега.
Существующий **Release Android artifacts** затем соберёт, подпишет и опубликует APK.
Это не заменяет [проверку релиза на устройствах](release-testing.md).

Workflow уже находится в `main`. Добавление секретов не запускает выпуск;
workflow нужно явно запустить с новой версией.

## Первоначальная настройка

В **Settings → Secrets and variables → Actions** добавьте:

| Тип | Имя | Значение |
| --- | --- | --- |
| Secret | `OPENAI_API_KEY` | Ключ OpenAI API, только для генерации двух текстов changelog. |
| Variable / Secret | `OPENAI_RELEASE_MODEL` | Доступная вашему API-проекту модель с Responses API Structured Outputs, например `gpt-6.1-sol`. Если заданы оба варианта, Variables имеют приоритет над Secrets. Неявного выбора другой модели нет. |
| Secret | `RELEASE_BOT_TOKEN` | Fine-grained PAT только для этого репозитория: Contents read/write, Pull requests read/write, Actions read. |

Сохраните существующие секреты подписи для сборки по тегу:
`ANDROID_SIGNING_KEY_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD`. Подготовка и проверки PR их не получают.

Разрешите squash merge. Защитите `main`, сделайте обязательными `Change scope`,
`Python tests and style`, `Native Go tests`, `Android tests and checks`,
`Android emulator API 26 / Device tests`, `Android emulator API 35 / Device tests`
и отключите обход правил для бота. Если правила требуют человеческое ревью, одобрите созданный
PR самостоятельно: workflow не одобряет себя и не обходит запрет. После отказа
merge можно одобрить PR и перезапустить упавший finalize job. Merge queue этим
workflow с прямым squash merge не поддерживается. Правила тегов должны разрешать
боту создавать `v*`.

Отдельный аккаунт бота позволяет отделить его права от ваших. В первой реализации
используется PAT; выпуск/продление GitHub App installation token не реализованы.
Не сохраняйте короткоживущий installation token как постоянный секрет.

Отдельный токен нужен, поскольку, согласно GitHub, PR от `GITHUB_TOKEN` требуют
одобрения запуска workflow, а его push не запускает следующий workflow. PAT
позволяет автоматически запустить PR CI и сборку после создания тега.
[Поведение GitHub token](https://docs.github.com/en/actions/concepts/security/github_token).

## Последовательность

1. Checkout выбранного commit `main`. Остановка, если `main` уже изменился,
   checkout грязный, версия некорректна либо ветка/тег уже существуют.
2. Выбор максимального стабильного `vX.Y.Z`, достижимого из этого commit.
   В OpenAI передаются заголовки/тела commit и diff statistics после тега.
   Код, repository secrets и материалы подписи в запрос не входят. История
   длиннее 100 000 символов приводит к остановке, а не молчаливому усечению.
   Запросы оплачиваются API-проектом; unit-тесты не вызывают живой API.
3. Проверка полного структурированного ответа: только EN/RU, по 1–500 символов.
   Отказ, незавершённый/некорректный ответ и ошибки API останавливают процесс
   до записи релизных файлов. Модель пишет только текст: команды, номера версий,
   merge и тег обрабатывает обычный код. Формат JSON не гарантирует достоверность;
   тексты доступны в PR и Actions summary.
4. Создание `release/vX.Y.Z`, обновление `versionName` и увеличение
   `versionCodeBase` на единицу в `app/build.gradle.kts`. Формула
   `base * 1000 + ABI offset` сохраняется для F-Droid и обновлений того же варианта.
   Например base 14 → 15: universal `15000`, arm `15001`, arm64 `15002`,
   x86 `15003`, x86_64 `15004`.
5. Добавление для этого примера
   `fastlane/metadata/android/{en-US,ru-RU}/changelogs/15000.txt`–`15004.txt`
   и PR в `main`. В каждом языке один текст сохраняется для universal и всех ABI APK,
   чтобы F-Droid находил его по коду версии APK.
   Исторические changelog и описания карточки не переписываются.
6. Ожидание до 60 минут **CI / pull_request** именно для этого PR и head SHA.
   Release-ветки запускают все наборы тестов уже с первой попытки. Все шесть
   перечисленных выше обязательных job должны получить `success`: skipped, neutral, failure и
   cancelled недостаточны. Записанная база сравнения должна совпасть с parent
   релизного commit. Номер PR также сохраняется успешным шагом CI: GitHub
   может убрать связь run с PR после merge, а этот шаг позволяет проверить
   повторное создание тега.
7. Повторная проверка PR и неизменности `main`, squash merge с ожидаемым head SHA.
   Правила GitHub продолжают действовать. Проверка принадлежности итогового
   commit к `main` и точного совпадения его дерева с проверенным head.
8. Lightweight-тег `vX.Y.Z` ставится **на фактический commit после merge**,
   не на head ветки и не на более поздний случайный `main`. PAT запускает обычную
   сборку по тегу. Новый GitHub Release использует те же EN/RU тексты из Git.
   Повтор finalize принимает уже существующий правильный тег, но никогда
   не переставляет тег, указывающий на другой commit.

Новая версия должна быть выше версии в коде и последнего стабильного тега.
Prerelease и автоматический выбор номера не поддерживаются. Подготовка и finalize
исполняют доверенные исходники workflow, а не код из релизного PR. Релизный commit
может менять только два поля версии Gradle и десять файлов changelog (пять кодов APK на язык). Параллельные запуски
подготовки выполняются последовательно.

## Ошибки и восстановление

Повтор не делает force-push, не двигает теги и не переписывает историю. Упавшая
проверка оставляет доступный PR без тега. После исправления/повтора CI выбирайте
**Re-run failed jobs** у workflow подготовки, если head и base PR не изменились.
Повтор использует результаты подготовки, не оплачивая ещё один ответ API.
Если merge уже прошёл, а создание тега не удалось, повтор finalize заново
проверит CI/дерево и создаст или проверит тег без повторного merge.

Если `main` или head изменились, старый finalize не должен одобрять новый код.
Осознанно обновите ветку так, чтобы единственный релизный commit был основан
на текущем `main`, дождитесь полного CI, затем завершите выпуск с явно выбранным
новым head из доверенного checkout `main`, с авторизованным `gh` и Fastlane:

```sh
export GITHUB_REPOSITORY=andre487/AndroidMegaProxy
bundle exec fastlane android release_finish version:0.1.2 pr:123 head:FULL_40_CHARACTER_SHA
```

Скрипт fetch-ит PR для проверки, но не делает checkout и не исполняет его код.
Команда выполняет merge и тег; это не dry run. Если ветка была отправлена, но
создание PR не удалось, проверьте её, создайте PR вручную и используйте тот же
способ восстановления. Новый запуск подготовки не подхватывает и не заменяет
существующую ветку молча. При сбое сборки по тегу повторяйте эту сборку, не двигая
тег. Внешняя заявка/recipe F-Droid автоматически не обновляются.

Ручная подготовка: `bundle exec fastlane android release_prepare version:0.1.2`
из чистого актуального checkout `main`, с теми же API/token/model настройками.

Формат API: [OpenAI Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs).
Условия merge: [GitHub merge API](https://docs.github.com/en/rest/pulls/pulls#merge-a-pull-request).

[English version](../en/release-automation.md)
