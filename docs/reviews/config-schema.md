# Configuration schema compatibility audit — 2026-10-06

Compared Android's `ConfigTransfer`, model enums and merge flow on current `main`
with MegaProxyConfig commit `9427cf235d6c112cd97ae82dc34d5d9b02aa42b7`
and BrowserMegaProxy's schema renewal pattern.

The Android v8 baseline is current for exported keys, proxy transports, TLS/SSH/DNS
presets, authentication modes, failover modes and secret inclusion flags. No Android
schema additions or version change are needed. Tests now validate actual ConfigStore
exports for all four transports and all password/private-key inclusion combinations
against both upstream schemas; enum and checksum checks detect contract drift.

The schemas describe canonical v8 documents, not every input accepted by Android.
Import intentionally accepts versions 1–8, omitted legacy fields, default ports and
future enum values, and clamps some numeric settings. Strict schema validation must
not replace that parser. IPv6 proxy literals are schema-valid but are rejected by
Android's existing decoder; the upstream documentation already records this limit.
Schema validation also cannot enforce unique profile IDs or ID-reference integrity;
consumer merge checks remain responsible. Blank unfinished local profiles can be
exported today but are not canonical usable profiles (their empty host fails the
schema and import). The export matrix covers configured profiles, not unfinished drafts.

Browser settings are optional shared-schema additions and are discarded by Android.
The upstream import-warning prose currently combines unsupported and undocumented
fields into one general message and forbids naming platforms. This change follows
the requested Android behavior instead: the existing import result includes at most
one browser-settings notice and one undocumented-fields notice. Neither reveals key
names or values, adds another confirmation, nor preserves discarded fields.

The upstream browser schema is behind BrowserMegaProxy's local overrides for dynamic
subscription source IDs and `browser.routing.strategy`. Android does not apply these
settings: documented browser fields get the browser notice, and a `strategy` key
not yet present upstream also gets the undocumented-fields notice. Android vendors
the exact upstream files without silently adopting another consumer's local overrides.

Renew with `bundle exec fastlane android renew_config_schema [ref:FULL_SHA]`, review
the schema/example changes and lock file together, then run `android_checks`.
Tests use committed files offline. The draft 2020-12 validator is a JVM test dependency;
the app uses only org.json and the bundled shared schema for field recognition.
