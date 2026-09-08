# Privacy policy review

Reviewed September 8, 2026 against the application source and Google's published requirements.
This review addresses the policy text; it does not certify store declarations or jurisdiction-specific
legal compliance.

## Necessary disclosures

The policy should explain the data used, its purpose, recipients, protection and deletion.
[Google Play's User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311?rd=1)
requires these disclosures and a privacy contact, including for apps without analytics. It treats
installed-app information and authentication data as sensitive. Local processing therefore still
needs an accurate explanation even when the developer receives no automatic uploads.

Keep the distinctions between local storage, traffic forwarded to user-chosen servers, external
DNS/diagnostic services and voluntary support correspondence. Avoid a blanket “no data collected”
claim, because the developer receives emails that users choose to send.

## Changes to the text

- Remove the cipher name and application-TLS implementation details. Retain the user-relevant
  statement that stored passwords/private keys are encrypted with an Android Keystore-held key.
- Link to README for the diagnostic endpoint inventory rather than duplicating every hostname.
  Retain recipient categories, purposes, and the difference between the source IP exposed during
  direct bootstrap DNS and the proxy exit IP exposed during diagnostics.
- Add installed-app visibility and routing selections, including their presence in profile exports
  (`SplitTunnelScreen`, `ConfigStore`, `ConfigTransfer`). This is local functionality, not an uploaded
  application inventory.
- Describe the voluntary report payload: model, OS/app versions, connection-setting summary and
  filtered logs (`FeedbackEmail`). Choosing a receiving application already gives it the shared
  copy; sending an email is a separate user action. Avoid implying that cancellation removes copies
  or drafts held by that application.
- Explain local log rotation/manual clearing and deletion of cached reports, and distinguish
  exported/shared copies from app data (`PersistentDiagnosticLog`, `FeedbackEmail`).
- Describe support emails as data received by the developer, including the sender address and
  attachments. The developer confirmed that they are retained until the reported issue is fixed,
  then deleted. This is an operational practice, not an app-enforced expiration timer.

## Remaining distribution work

The app now includes a privacy-policy link at the bottom of Settings, with English and Russian
labels and an error message if the browser cannot be opened. It points to the public PRIVACY.md
on main. Google's User Data policy also requires a URL in Play Console; verify that the published
URL remains public and readable. The repository policy alone does
not establish that the Play Console field or Data safety answers are correct; those settings were
not inspected in this review.

The [VpnService guidance](https://support.google.com/googleplay/android-developer/answer/12564964?hl=en)
also addresses in-app disclosure/consent for personal or sensitive data handled through VpnService.
A privacy policy does not substitute for that disclosure. Review the actual onboarding/consent
flow against the final distribution declarations separately; no consent UI was added here.
