# Работа с Fastlane

MegaProxy использует [Fastlane](https://fastlane.tools/) как основной интерфейс командной строки
для тестов и сборочных артефактов. Gradle, Go и скрипты из `scripts/` остаются низкоуровневой
реализацией сборки, а Fastlane предоставляет одинаковые именованные сценарии для локальной работы
и CI.

Официальные материалы: [установка Fastlane для Android](https://docs.fastlane.tools/getting-started/android/setup/),
[настройка через Bundler и Gemfile](https://docs.fastlane.tools/getting-started/android/setup/#use-a-gemfile)
и описание [Gradle action](https://docs.fastlane.tools/actions/gradle/).

## Установка

Сначала установите зависимости проекта из раздела
[Building from source](../../README.md#building-from-source): JDK 21, Go, Android SDK и Android NDK.
Затем установите Ruby 3.4.10 — эта версия зафиксирована в `.ruby-version`. Рекомендуется менеджер
версий Ruby; системный Ruby из macOS использовать не следует.

Установите актуальный Bundler и зафиксированные проектом зависимости из корня репозитория:

```shell
gem install bundler
bundle install
```

Fastlane всегда следует запускать через Bundler, чтобы использовались точные версии из
`Gemfile.lock`:

```shell
bundle exec fastlane lanes
```

Эта команда показывает lanes, доступные в текущей версии проекта.

## Поддерживаемые команды

| Команда | Результат |
| --- | --- |
| `bundle exec fastlane android python_format` | Форматирует Python-скрипты и сортирует импорты через Black/isort. |
| `bundle exec fastlane android python_checks` | Проверяет стиль Python и контрактные тесты скриптов без устройств. |
| `bundle exec fastlane android native_tests` | Запускает все Go-тесты с race detector. |
| `bundle exec fastlane android android_checks` | Собирает native AAR, запускает Android unit-тесты и lint, собирает debug APK, затем собирает и проверяет unsigned release APK. Команда отклоняет переменные release-подписи. |
| `bundle exec fastlane android test` | Выполняет `native_tests` и `android_checks`; основная команда перед коммитом. |
| `bundle exec fastlane android debug_artifact` | Собирает `app/build/outputs/apk/debug/app-debug.apk`. |
| `bundle exec fastlane android release_artifacts` | Собирает и проверяет подписанные APK, AAB, native debug symbols и `SHA256SUMS` в `dist/release`. |
| `bundle exec fastlane android selectel_contract_tests` | Проверяет освобождение аренды и разбор результатов без аренды устройства. |
| `bundle exec fastlane android ui_test_artifacts` | Запускает контрактные тесты Selectel и собирает debug APK приложения и UI-тестов. |
| `bundle exec fastlane android ui_test_artifacts profile:additional` | Собирает APK для дополнительных необязательных конфигураций. |
| `bundle exec fastlane android selectel_probe` | Проверяет доступ и наличие устройств без аренды. |
| `bundle exec fastlane android selectel_acquire` | Арендует одно устройство и сохраняет журнал; APK должны быть собраны заранее. |
| `bundle exec fastlane android selectel_run` | Запускает тесты на устройстве из журнала аренды. |
| `bundle exec fastlane android selectel_release` | Удаляет записанную аренду и временный ADB-ключ; повторный вызов безопасен. |
| `bundle exec fastlane android selectel_ui_tests` | Арендует устройство, выполняет готовые тесты и освобождает аренду в finally. |
| `bundle exec fastlane android selectel_ui_tests profile:additional` | Последовательно проверяет четыре дополнительные конфигурации, исключая обязательный Android 15. |

Для release lane нужна конфигурация подписи из раздела
[Signed release builds](../../README.md#signed-release-builds). Lane только собирает артефакты: он
не загружает их в Google Play и не публикует GitHub Release. GitHub Actions запускает тот же lane,
а публикацию GitHub Release выполняет отдельным шагом.

В CI для pull request запускается `android_checks`, но никогда не `release_artifacts`. Job не
получает секреты подписи, отклоняет случайно переданную конфигурацию подписи и через Android
`apksigner` проверяет отсутствие подписи у `app-release-unsigned.apk`. Отдельный lane
`debug_artifact` создаёт APK, подписанный только стандартным одноразовым debug-ключом Android; ключ
официального релиза MegaProxy при этом не используется.

Для pull request GitHub Actions загружает debug APK и unsigned release APK как два отдельных
workflow artifact с понятными именами. Прямые ссылки на скачивание выводятся в job summary проверки
Android, а сами файлы хранятся 14 дней. После успешного CI отдельный доверенный workflow с событием
`workflow_run` создаёт или обновляет один комментарий со ссылками в pull request. Он не делает
checkout, не скачивает и не исполняет код или артефакты из PR. Это только тестовые артефакты: ни
один из APK не подписан официальным release-ключом MegaProxy, не публикуется в GitHub Releases и не
отправляется в магазин приложений.

## Обновление Fastlane

Fastlane следует обновлять явно, после чего проверить и закоммитить оба файла зависимостей:

```shell
bundle update fastlane
bundle exec fastlane lanes
bundle exec fastlane android test
```

Проверьте изменения в `Gemfile` и `Gemfile.lock`. Официальная документация Fastlane рекомендует
хранить lock-файл в репозитории и использовать `bundle exec fastlane` локально и в CI.

[English version](../en/fastlane.md)

[Настройка UI-тестов Selectel](selectel-ui-tests.md).

## Инструменты разработки Python

Сами скрипты используют стандартную библиотеку Python. Зафиксированные форматтеры нужны только для
разработки; настройки Black и isort находятся в `pyproject.toml`. Отдельная обязательная проверка
`Python tests and style` запускает проверку форматирования и Python-тесты при изменениях Python или общих файлов CI/сборки в полном diff PR.
`Change scope` выбирает нужные проверки: изменения только Python/документации пропускают Android
и UI. Изменения только документации пропускают тестовые задания. При пропуске Android-сборки APK не публикуются.

```sh
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r requirements-dev.txt
bundle exec fastlane android python_format
bundle exec fastlane android python_checks
```

Переменная `PYTHON` переопределяет интерпретатор форматтеров.
