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
| `bundle exec fastlane android native_tests` | Запускает все Go-тесты с race detector. |
| `bundle exec fastlane android android_checks` | Собирает native AAR, запускает Android unit-тесты и lint, собирает debug APK, затем собирает и проверяет unsigned release APK. Команда отклоняет переменные release-подписи. |
| `bundle exec fastlane android test` | Выполняет `native_tests` и `android_checks`; основная команда перед коммитом. |
| `bundle exec fastlane android debug_artifact` | Собирает `app/build/outputs/apk/debug/app-debug.apk`. |
| `bundle exec fastlane android release_artifacts` | Собирает и проверяет подписанные APK, AAB, native debug symbols и `SHA256SUMS` в `dist/release`. |

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

## Выбор проверок CI и инструменты Python

Для каждого набора CI сравнивает текущий head PR с последним успешно проверенным коммитом-предком
для этого набора. Упавшие, отменённые и пропущенные задания не считаются успешной проверкой.
Кандидат должен относиться к тому же PR и репозиторию, иметь ту же сохранённую базу PR и быть старше
текущего прогона. Коммиты из отброшенной после rebase истории не используются. Через gh проверяются
последние 30 завершённых CI-прогонов ветки. Если истории нет, API недоступен или старый прогон не
сохранял базу, используется полный diff PR. Пуши в main сравниваются по началу и концу пуша.
База сравнения и решение для каждого набора видны в summary Actions. При повторе собственный run ID
не используется как предыдущая проверка.

Изменения только Python запускают Python-проверки; изменения только документации пропускают
тестовые задания. Production-код Go включает Go и Android, изменения только Go-тестов — Go.
Общие файлы CI/Fastlane и неизвестные пути включают все проверки. Ошибка вычисления diff приводит
к ошибке `Change scope`, а не к тихому пропуску тестов. При пропуске Android-сборки APK не публикуются.

Установите закреплённые версии инструментов в виртуальное окружение:

```sh
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r requirements-dev.txt
bundle exec fastlane android python_format
bundle exec fastlane android python_checks
```

`python_format` применяет isort и Black. `python_tests` запускает только Python-тесты;
`python_checks` проверяет форматирование/импорты и запускает тесты. Все три команды учитывают `PYTHON`
(по умолчанию `python3`). Скрипты используют только стандартную библиотеку Python. Задание CI
`Python tests and style` выполняется независимо от Android-сборки.

## Интерактивный запуск GitHub Actions

```sh
python3 scripts/github_actions.py
python3 scripts/github_actions.py --dry-run
python3 scripts/github_actions.py --yes
```

Выберите открытый PR и повтор всего CI либо только упавших заданий. Нужен GitHub CLI (`gh`)
с авторизацией (`gh auth login`, `GH_TOKEN` или `GITHUB_TOKEN`). Скрипт вызывает штатные команды gh,
без своего HTTP-клиента и хранения токенов. `--repo OWNER/REPO` переопределяет репозиторий.
`--yes` / `-y` пропускает последнее подтверждение, сохраняя меню и проверку актуальности коммита;
`--dry-run` всегда запрещает запуск. `q` или Ctrl+C отменяет операцию. В меню только открытые PR
этого репозитория, без форков. Используется существующий завершённый CI-прогон текущего коммита PR.
Активные задания и отсутствие прогона приводят к отказу; обычно CI начинается после пуша.
Повтор только упавших заданий требует failed-прогона; cancelled можно повторить целиком.
Ошибки/таймауты запуска не приводят к автоматической повторной отправке.
Повтор сохраняет исходный коммит и базу diff того прогона; для пересчёта относительно обновлённой
базы PR нужен новый пуш. В меню нет запуска устройств или release-workflow.
