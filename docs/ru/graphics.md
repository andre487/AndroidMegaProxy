# Обновление графики

[English](../en/graphics.md) · [Команды Fastlane](fastlane.md)

[MegaProxyIdentity](https://github.com/andre487/MegaProxyIdentity) — источник истины для графики
MegaProxy. Правьте SVG там, затем генерируйте PNG/XML, которые коммитятся в AndroidMegaProxy.
README использует точную копию `icon-radius.svg` из Identity в `docs/assets`, обновляемую
тем же скриптом. Единственный независимо поддерживаемый локальный графический исходник —
SVG кнопки GitHub с лицензиями. Скриншоты
обновляются отдельно и не перезаписываются. Старые оригиналы, включая прежний social preview,
сохранены в `Generative/AndroidMegaProxy` репозитория Identity; архивные PNG не участвуют в генерации.

Установите `gh` и авторизуйтесь, установите `rsvg-convert` из librsvg (`brew install librsvg` на
macOS или `sudo apt-get install librsvg2-bin` на Debian/Ubuntu) и настройте Bundler проекта.
Python использует только стандартную библиотеку; Pillow и ImageMagick не нужны.

```sh
bundle exec fastlane android update_graphics
# Воспроизвести проверенную версию Identity:
bundle exec fastlane android update_graphics ref:FULL_SHA
```

Lane вызывает `scripts/update_graphics.py`. Скрипт определяет один коммит Identity и получает
все исходники по этому SHA. Файлы заменяются только после успешного скачивания и рендеринга
всех изображений. Проверьте результат и закоммитьте его вместе с
`assets/branding/identity-lock.json`. Манифест содержит SHA-256 исходников и результатов, а также
версию рендерера; для побайтового воспроизведения используйте ту же версию librsvg.
Обычные сборки приложения/F-Droid и CI используют готовые файлы из Git, без скачивания Identity
и без запуска рендерера.

| Исходник | Результат |
| --- | --- |
| `SVG/icon.svg` | Слои фона/переднего плана adaptive icon |
| `SVG/icon-radius.svg`, `SVG/icon-round.svg` | Обычные и круглые launcher-иконки для пяти плотностей; `icon-radius.svg` также используется для иконок магазина 512×512 |
| `SVG/icon-taskbar.svg` | Белые значки уведомлений с прозрачностью размером 24dp для пяти плотностей |
| `SVG/feature-graphic.svg` | Store feature graphic EN/RU 1024×500 |
| `SVG/social-preview.svg` | Social preview 1280×640 в `docs/assets/social-preview.png` |
| Локальный `docs/assets/get-it-on-github.svg` | PNG кнопки GitHub 1292×500; локальные лицензии сохраняются |

Adaptive icon сохраняет фон и кольца SVG, а логотип уменьшается до 75% холста 108dp, чтобы
вписаться в безопасную окружность Android. Извлечение использует ID слоёв `icon-2` и `Background`;
изменение структуры SVG требует проверки рецепта. Social preview генерируется из отдельного
SVG Identity. Лицензия Identity сохраняется в
`assets/branding/IDENTITY-LICENSE`; у кнопки GitHub остаются отдельные лицензии.

`bundle exec fastlane android python_checks` проверяет хеши готовых файлов, размеры, закрепление
исходников, разделение слоёв и сохранность старых файлов при ошибке рендеринга. Изображения,
не перечисленные в манифесте, запрещены, кроме скриншотов и SVG кнопки GitHub.
