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
Android, а сами файлы хранятся 14 дней. Это только тестовые артефакты: ни один из APK не подписан
официальным release-ключом MegaProxy, не публикуется в GitHub Releases и не отправляется в магазин
приложений.

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
