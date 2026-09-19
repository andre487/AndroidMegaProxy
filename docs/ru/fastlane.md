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

Скрипты нативной и релизной сборки находят JDK 21 через `JAVA_HOME`, macOS `java_home` или `java` в `PATH`. Явно заданный несовместимый JDK приводит к понятной ошибке до сборки; путь установки Homebrew не предполагается.

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
| `bundle exec fastlane android python_format` | Форматирует Python зафиксированными Black и isort. |
| `bundle exec fastlane android python_tests` | Запускает Python unit-тесты. |
| `bundle exec fastlane android python_checks` | Проверяет форматирование, порядок импортов и Python-тесты. |
| `bundle exec fastlane android native_fuzz` | Запускает fuzz-тесты нативных парсеров на 20 секунд с двумя worker-процессами. |
| `bundle exec fastlane android native_tests` | Запускает все Go-тесты с race detector. |
| `bundle exec fastlane android android_checks` | Собирает native AAR, запускает Android unit-тесты и lint, собирает debug APK, затем собирает и проверяет unsigned release APK. Команда отклоняет переменные release-подписи. |
| `bundle exec fastlane android test` | Выполняет `native_tests` и `android_checks`; основная команда перед коммитом. |
| `bundle exec fastlane android debug_artifact` | Собирает `app/build/outputs/apk/debug/app-debug.apk`. |
| `bundle exec fastlane android release_artifacts` | Собирает и проверяет подписанные APK, AAB, native debug symbols и `SHA256SUMS` в `dist/release`. |
| `bundle exec fastlane android play_release` | Загружает готовый подписанный AAB и native symbols в Google Play; по умолчанию создаёт internal-черновик. |

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

## Релизы в Google Play

`play_release` загружает готовый подписанный AAB и соответствующие native symbols для
`net.megaproxy487`. Соберите их через `release_artifacts` или скачайте оба файла из одного
проверенного GitHub Release. Lane не собирает и не подписывает файлы. Для нового релиза нужен
неиспользованный возрастающий `versionCode`. AAB должен быть подписан upload key,
зарегистрированным в Play Console.

Перед первой загрузкой через API создайте приложение в Play Console, настройте Play App Signing
и вручную загрузите первоначальную сборку. Включите Google Play Developer API в проекте Google
Cloud, создайте сервисный аккаунт и пригласите его email в Play Console с доступом к приложению
и правами для нужных тестовых/production-треков. Храните JSON-ключ вне репозитория и передавайте его содержимое через `SUPPLY_JSON_KEY_DATA`.
См. [настройку Google API](https://developers.google.com/android-publisher/getting_started) и
[настройку Fastlane supply](https://docs.fastlane.tools/actions/upload_to_play_store/#setup).

Запускайте из корня репозитория:

```shell
export SUPPLY_JSON_KEY_DATA="$(cat "$HOME/.my-tokens/megaproxy-play.json")"
bundle exec fastlane android release_artifacts
bundle exec fastlane android play_release validate_only:true
bundle exec fastlane android play_release
```

`SUPPLY_JSON_KEY_DATA` — штатная переменная окружения Fastlane с полным содержимым JSON-ключа,
а не путём к файлу или строкой Base64. Если локальное окружение уже задаёт её, пропустите `export`
выше. Файл в примере — лишь вариант локального хранения; сам lane файлы ключей не читает.

В GitHub создайте Actions secret `SUPPLY_JSON_KEY_DATA` с тем же полным JSON.
Существующий release-workflow по тегу передаёт его только шагу загрузки:

```yaml
- name: Upload Google Play draft
  env:
    SUPPLY_JSON_KEY_DATA: ${{ secrets.SUPPLY_JSON_KEY_DATA }}
  run: bundle exec fastlane android play_release track:internal release_status:draft
```

После сборки и проверки артефактов и публикации GitHub Release workflow загружает соответствующие
AAB и symbols из `dist/release` как internal-черновик. Отсутствие секрета или ошибка загрузки в Play
завершает workflow с ошибкой; уже опубликованный GitHub Release остаётся доступным. Не передавайте
ключ аргументом lane и не выводите его в логи. Workflow для PR не должны получать этот ключ.

В существующий локальный shell env-файл добавьте `export SUPPLY_JSON_KEY_DATA=...` с JSON в
корректных shell-кавычках и загрузите файл перед запуском Fastlane. Например, для локальной
релизной конфигурации:

```shell
source "$HOME/.config/megaproxy/release.env"
bundle exec fastlane android play_release
```

Храните env-файл вне репозитория с правами `0600`.

По умолчанию создаётся **черновик в треке internal**. `validate_only:true` загружает файлы во
временную транзакцию Google Play и проверяет её через API без сохранения релиза; нужны ключ
и сеть, это не локальный dry run. Проверьте и завершите черновик в Play Console.
Для нового AAB, который нужно сразу отправить тестировщикам или в production, явно укажите:

```shell
bundle exec fastlane android play_release track:internal release_status:completed
bundle exec fastlane android play_release track:production release_status:completed
```

Выполняйте только команду для нужного направления. Проверка Google, доступность публикации для
приложения и managed publishing могут задержать появление релиза. Уже загруженную версию
продвигайте через Play Console: lane загружает новый AAB и не продвигает существующие релизы.

| Параметр | Значение по умолчанию / поведение |
| --- | --- |
| `aab` | `dist/release/mega-proxy.aab` |
| `symbols` | `dist/release/mega-proxy-native-debug-symbols.zip`; обязателен и должен соответствовать AAB |
| `track` | `internal`; также принимает `alpha`, `beta`, `production` или ID пользовательского трека |
| `release_status` | `draft`; поддерживаются `draft` и `completed` |
| `validate_only` | `false`; принимает только `true` или `false` |

`MEGAPROXY_RELEASE_DIR` меняет каталог артефактов по умолчанию. Относительные пути считаются от
корня репозитория. Метаданные, changelog, изображения и скриншоты не загружаются; каталог F-Droid
`fastlane/metadata/android` остаётся отдельным от управления карточкой Play.
Workflow по тегу публикует GitHub Release и internal-черновик Google Play. CI для PR не должен
получать JSON-ключ Play или вызывать `play_release`.

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
сохранял базу, используется полный diff PR. Каждый пуш в main запускает все наборы без фильтрации по diff и истории.
Бейдж README явно привязан к `badge.svg?branch=main&event=push`.
База сравнения и решение для каждого набора видны в summary Actions. При повторе собственный run ID
не используется как предыдущая проверка.

В первичном прогоне PR изменения только Python запускают Python-проверки; изменения только документации пропускают
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

Выберите открытый PR и повтор всего CI **включая пропущенные проверки** либо только упавших заданий. Нужен GitHub CLI (`gh`)
с авторизацией (`gh auth login`, `GH_TOKEN` или `GITHUB_TOKEN`). Скрипт вызывает штатные команды gh,
без своего HTTP-клиента и хранения токенов. `--repo OWNER/REPO` переопределяет репозиторий.
`--yes` / `-y` пропускает последнее подтверждение, сохраняя меню и проверку актуальности коммита;
`--dry-run` всегда запрещает запуск. `q` или Ctrl+C отменяет операцию. В меню только открытые PR
этого репозитория, без форков. Используется существующий завершённый CI-прогон текущего коммита PR.
Активные задания и отсутствие прогона приводят к отказу; обычно CI начинается после пуша.
Повтор только упавших заданий требует failed-прогона; cancelled можно повторить целиком.
Ошибки/таймауты запуска не приводят к автоматической повторной отправке.
Полный повтор заново запускает Change scope. Начиная со второй попытки он включает Android
(в том числе Compose UI-тесты), native и Python без фильтрации по diff и истории. Так же работает
кнопка Re-run all jobs в GitHub. Повтор только упавших сохраняет прежний состав проверок, кроме
случая, когда сам Change scope упал и запускается повторно: тогда включаются все проверки.
[GitHub сохраняет исходный коммит при повторе](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/re-run-workflows-and-jobs).
Старые прогоны, созданные до этого изменения workflow, сохраняют прежнюю фильтрацию; сначала нужен
новый пуш. В меню нет запуска устройств или release-workflow.

### Native parser fuzzing

Ограниченный локальный fuzz-прогон парсеров конфигурации, JA3 и DNS: 20 секунд, два worker-процесса. Начальный корпус также проверяется в `native_tests`. Устройство не требуется.

```shell
bundle exec fastlane android native_fuzz
```

## Compose UI-тесты без эмулятора

`bundle exec fastlane android android_checks` (и `android test`) запускает Compose-тесты
Robolectric из `app/src/test` вместе с существующими JVM-тестами. Они входят в обычную
Android-проверку PR; устройство, ADB и KVM не нужны.

Тесты взаимодействия покрывают основные пользовательские сценарии:

- Главный экран: подключение/переподключение/остановку, выдачу и отказ VPN-разрешения,
  невалидный профиль, конфликты Always-on, блокировку действий при подключении и выбор профиля.
- Профили: создание черновика, редактирование и проверку порта, поля SSH/HTTPS Jump,
  подтверждение обхода сертификата, клонирование/удаление, ошибки импорта и экспорт без паролей.
- Настройки: единицы трафика, TLS fingerprint, подтверждение failover и режим выбранных приложений.
- Навигацию: разделы настроек и возврат, создание профиля, диагностику и SSH-подтверждение.
- Диагностику: выполнение/успех/ошибку, выходной IP, копирование лога и подтверждение очистки.
- Доверие SSH: успешное сохранение, ошибку и повтор, блокировку действий/Back во время
  сохранения и отмену диагностики (`SshHostKeyUiTest`).

`MainUiTestBase` использует реальные экраны и ConfigStore с тестовым Keystore в памяти.
Robolectric фиксирует команды сервиса и подставляет результаты разрешений и выбора файлов;
чтение статистики соединения подменяется. Тесты не запускают VPN-трафик, Go JNI и Keystore
устройства. Зафиксированы обычный тестовый Application, Android API 35 и английские ресурсы;
при первом запуске Robolectric скачивает Android runtime из Maven Central.

Новые проверки поведения добавляйте с теми же runner и Compose rule. Операции платформы
оставляйте на границе экрана и подставляйте управляемые реализации; избегайте sleep и сети.
Это проверки взаимодействия, а не сравнение скриншотов или полная проверка на устройстве.
См. [настройку Robolectric](https://robolectric.org/getting-started/).
