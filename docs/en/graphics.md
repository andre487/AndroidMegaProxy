# Updating artwork

[Русский](../ru/graphics.md) · [Fastlane commands](fastlane.md)

[MegaProxyIdentity](https://github.com/andre487/MegaProxyIdentity) is the source of truth for
MegaProxy artwork. Edit its SVGs, then regenerate AndroidMegaProxy's committed PNG/XML outputs.
The README uses a verbatim copy of Identity’s `icon-radius.svg` in `docs/assets`, updated
by the same script. The only independently maintained local graphic source is the GitHub
download badge SVG with its notices. Screenshots
remain separate and are not overwritten. Retired originals, including the old social preview,
are archived in Identity's `Generative/AndroidMegaProxy`; archived PNGs are not generation inputs.

Install `gh` and authenticate it, install librsvg's `rsvg-convert` (`brew install librsvg` on
macOS or `sudo apt-get install librsvg2-bin` on Debian/Ubuntu), and use the project's Bundler setup.
Python uses only the standard library; generation does not require Pillow or ImageMagick.

```sh
bundle exec fastlane android update_graphics
# Reproduce a reviewed Identity revision:
bundle exec fastlane android update_graphics ref:FULL_SHA
```

The lane calls `scripts/update_graphics.py`. It resolves one Identity commit and downloads all
inputs at that SHA before rendering. It replaces outputs only after every download and render
succeeds. Review the images and commit them together with `assets/branding/identity-lock.json`.
The lock records source/output SHA-256 hashes and the renderer version; use the same librsvg
version for byte-for-byte reproduction. Normal app/F-Droid builds and CI use committed outputs,
without downloading Identity or running a renderer.

| Source | Generated outputs |
| --- | --- |
| `SVG/icon.svg` | Adaptive background/foreground layers |
| `SVG/icon-radius.svg`, `SVG/icon-round.svg` | Legacy square/round launcher icons for all five densities; `icon-radius.svg` also generates 512×512 store icons |
| `SVG/icon-taskbar.svg` | White alpha-only notification icons at 24dp for all five densities |
| `SVG/feature-graphic.svg` | 1024×500 EN/RU store feature graphics |
| `SVG/social-preview.svg` | 1280×640 social preview saved as `docs/assets/social-preview.png` |
| Local `docs/assets/get-it-on-github.svg` | 1292×500 GitHub download badge PNG; local notices are retained |

Adaptive layers retain the SVG's background/rings and keep the wordmark inside Android's safe
circle by scaling it to 75% of the 108dp foreground canvas. The extraction uses the Identity
`icon-2` and `Background` layer IDs; a changed SVG structure requires reviewing the recipe.
The social preview is rendered directly from its dedicated Identity SVG.
Identity-derived artwork retains the upstream license in `assets/branding/IDENTITY-LICENSE`;
the GitHub badge retains its separate notices.

`bundle exec fastlane android python_checks` verifies committed hashes, output dimensions,
source pinning, layer extraction and preservation of existing files when rendering fails.
It also rejects untracked-by-manifest artwork outside screenshots and the GitHub badge source.

## Store screenshots

EN/RU screenshots live in `fastlane/metadata/android/<locale>/images/phoneScreenshots`.
The current set uses Android API 35, 1080×2400, light theme and demo profiles. Main-screen traffic
and the active source-validation status come from a local HTTPS fixture and the real VPN;
Diagnostics uses the example IP `203.0.113.42` and demonstration identity data. Speed transfers
and public IP-reputation checks are not run for these captures. Keep credentials and personal
addresses out of future captures; review both languages and keep the source-protection card fully visible.
