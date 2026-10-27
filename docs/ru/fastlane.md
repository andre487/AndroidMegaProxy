# Работа с Fastlane

Установите JDK 21, Go и Android SDK/NDK из [README](../../README.md#building-from-source),
Ruby 3.4.10 (`.ruby-version`), затем выполните `bundle install`. Всегда используйте `bundle exec`
и закреплённый `Gemfile.lock`. Скрипты native/release находят JDK 21 без пути конкретной машины;
явно заданный несовместимый `JAVA_HOME` приводит к ошибке. `bundle exec fastlane lanes` показывает команды текущего checkout.

## Команды

| Команда | Результат |
| --- | --- |
| `bundle exec fastlane android renew_config_schema` | Загружает и фиксирует схемы и примеры MegaProxyConfig; `ref:FULL_SHA` выбирает проверенную ревизию. |
| `bundle exec fastlane android update_graphics` | Генерирует графику Android/магазина/документации из SVG MegaProxyIdentity; `ref:FULL_SHA` воспроизводит закреплённую версию. |
| `bundle exec fastlane android python_format` | Форматирует Python зафиксированными Black и isort. |
| `bundle exec fastlane android python_tests` | Запускает Python unit-тесты. |
| `bundle exec fastlane android python_checks` | Проверяет форматирование, порядок импортов и Python-тесты. |
| `bundle exec fastlane android native_fuzz` | Запускает fuzz-тесты нативных парсеров на 20 секунд с двумя worker-процессами. |
| `bundle exec fastlane android native_tests` | Запускает все нативные Go-тесты и регрессии локальных патчей uQUIC с race detector. |
| `bundle exec fastlane android native_benchmarks` | Измеряет время и аллокации ядра в десяти сэмплах; [сравнение и профилирование](native-benchmarks.md). Опции: `bench`, `count`, `benchtime`, `cpu`, `output`, `profile:true`. |
| `bundle exec fastlane android native_integration` | Проверяет сетевой код с настоящими GOST/OpenSSH в Docker и race detector. |
| `bundle exec fastlane android android_checks` | Собирает native AAR, получает закреплённые SDK Robolectric через Gradle, запускает Android unit-тесты и lint, собирает debug APK, затем собирает и проверяет unsigned release APK. Команда отклоняет переменные release-подписи. |
| `bundle exec fastlane android device_test_build` | Собирает native AAR, debug APK и instrumentation APK. |
| `bundle exec fastlane android device_tests api:26` | Проверяет интеграционные сценарии на запущенном одноразовом эмуляторе API 26. |
| `bundle exec fastlane android device_tests api:35` | Проверяет интеграционные сценарии на запущенном одноразовом эмуляторе API 35. |
| `bundle exec fastlane android test` | Выполняет `native_tests` и `android_checks`; без Python, Docker-интеграции и тестов на эмуляторах. |
| `bundle exec fastlane android debug_artifact` | Собирает `app/build/outputs/apk/debug/app-debug.apk`. |
| `bundle exec fastlane android release_prepare version:0.1.2` | Генерирует EN/RU changelog, повышает версию и создаёт release PR (нужна настройка API/токенов). |
| `bundle exec fastlane android release_notes_preview version:1.1.3 ref:v1.1.3` | Выводит тезисные EN/RU тексты GitHub и F-Droid для указанного диапазона Git без публикации. |
| `bundle exec fastlane android release_finish version:0.1.2 pr:123 head:FULL_SHA` | Ждёт полного CI, сливает указанный head PR либо принимает существующий squash merge и тегирует проверенный результат. |
| `bundle exec fastlane android virus_total` | Проверяет подписанные APK в `dist/release` (или `artifacts:PATH`); нужен `VIRUSTOTAL_API_KEY`, обнаружения/ошибки API блокируют публикацию. |
| `bundle exec fastlane android release_artifacts` | Собирает и проверяет подписанные APK и `SHA256SUMS` в `dist/release`. |

## Выбор проверок и результаты

[Регламент тестирования](release-testing.md) определяет локальные, CI и релизные проверки и их границы.
`test` объединяет только native unit/race и Android JVM/lint/debug/unsigned-release проверки;
Python, Docker integration и тесты устройств запускаются отдельными lanes.

JUnit: `test-results/native/{unit,integration}.xml`, `test-results/python/`,
`app/build/test-results/testDebugUnitTest/`, `test-results/android-api{26,35}/`.
CI сохраняет семидневные `junit-*` артефакты и публикует результат каждого набора сразу по завершении.
Пропуск не считается новым успешным результатом. Для fork отчёты разбирает доверенный
`workflow_run`, не загружая и не исполняя код PR. Слияние ограничивают исходные проверки наборов.
Ссылки на debug/unsigned APK видны в summary задания и описании PR; срок хранения — 14 дней,
релизные секреты подписи недоступны. Использование описано в [установке](installation.md).

## Локальные интеграционные тесты эмулятора

Нужны одноразовый Google APIs эмулятор с английским системным UI и совпадающим API, Docker
и `ANDROID_SERIAL=emulator-5554` (укажите фактический serial). **Runner очищает данные MegaProxy.**
Физические устройства запрещены. В Linux фикстурам нужен беспарольный `sudo iptables` для блокировки
прямого доступа к origin; на macOS origin внутри Docker VM уже недоступен напрямую.

```sh
# Собрать один раз для ABI эмулятора; по умолчанию собираются все ABI.
MEGAPROXY_ANDROID_ABI=x86_64 bundle exec fastlane android device_test_build
bundle exec fastlane android device_tests api:35
```

Повторите `device_tests api:26` на отдельном эмуляторе API 26. Холодная загрузка с ускорением;
при запуске локального эмулятора очищайте `HTTP_PROXY`, `HTTPS_PROXY`, `ALL_PROXY`, `NO_PROXY`
и варианты в нижнем регистре. Без snapshot restore, программной эмуляции и повторов упавших тестов.
Отчёты, вывод instrumentation и Logcat сохраняются в каталоге API;
при сбое добавляются скриншот и UI hierarchy, если их удалось получить.

## Python и launcher

```sh
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r requirements-dev.txt
bundle exec fastlane android python_checks
```

`python_format` применяет закреплённые Black/isort; все Python lanes учитывают `PYTHON` (по умолчанию `python3`).
Runtime-скрипты используют stdlib и native `gh`. Для тестов JavaScript workflow нужен также Node.js.
`python3 scripts/github_actions.py` выбирает открытый PR и повторяет весь CI либо упавшие задания;
`--dry-run` показывает план, `--yes` убирает подтверждение, `--repo OWNER/REPO` задаёт репозиторий.
Нужны авторизованный `gh` и завершённый запуск для текущего head PR; head перепроверяется.

## Зависимости и релизы

`native_integration` требует Docker и доступ к registry/packages: создаёт одноразовые GOST/OpenSSH
и закрытые HTTP/UDP origins без личных серверов и паролей.
Проверяет production dialers, данные, авторизацию и доверие, но не Android routing/lifecycle.
Контейнеры/сети удаляются при выходе; после принудительного завершения возможна ручная очистка `megaproxy-test-*`.
`native_fuzz` запускает парсеры на 20 секунд/два worker; seeds входят в `native_tests`.

`renew_config_schema [ref:FULL_SHA]` получает MegaProxyConfig через авторизованный `gh`; фиксируйте
схемы, примеры, лицензию и SHA-256 lock вместе. CI читает только сохранённый snapshot.
Legacy import остаётся permissive; явный неподдерживаемый transport отклоняется. Fastlane обновляется
через `bundle update fastlane`: проверьте оба Gemfile, список lanes и затронутые проверки.

Перед локальной подписанной сборкой подготовьте закреплённые gomobile tools через Android build lane.
`release_artifacts` читает `MEGAPROXY_KEYSTORE_PATH`, `MEGAPROXY_KEY_ALIAS`,
`MEGAPROXY_KEY_PASSWORD_FILE` и необязательный `MEGAPROXY_KEY_PASSWORD`; результат — `dist/release`.
Defaults и проверка signer описаны в [релизном скрипте](../../scripts/build-release-apks.sh).

Подпись, API/token, публикация и восстановление: [автоматизация релиза](release-automation.md).
`release_finish` сливает и создаёт тег, это не dry run. PR не подписывает релизы и не отправляет APK в VirusTotal.

[English version](../en/fastlane.md)
