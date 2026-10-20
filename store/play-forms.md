# Draft answers for Play Console forms

## Data safety (submitted 2026-10-19)
- Collects or shares required data types: **Yes**. Encrypted in transit: **Yes**. No accounts; no external login.
- **Photos** and **Other user-generated content** (meal text): collected, not shared, not ephemeral,
  **optional** (only with cloud AI turned on), purpose *App functionality*. Sent to the AI provider the user picks.
- **Diagnostics** and **Device or other IDs**: collected by Google ML Kit (barcode, Gemini Nano) per its data
  disclosure; required, purpose *Analytics*.
- Deletion request question left blank: everything is stored on the device.

## Health apps declaration
- Features: **Nutrition and weight management / food logging.** Not a medical device; no diagnosis or treatment.

## Content rating (IARC questionnaire)
- Category: Utility / productivity (reference/health). No violence, sexuality, gambling, controlled substances,
  user-to-user interaction or location sharing. Expected rating: Everyone / PEGI 3.

## Target audience
- 18 and over (chosen 2026-10-19; can be widened later). Not in the Families program.

## Ads
- Contains ads: **No**.

## App access
- All features available without login. (Google Sheets export needs a Google account, optional.)

## Generative AI
- The app uses generative AI to estimate nutrition. In-app reporting: meal details → "Report a problem with this
  estimate", and Settings → About → "Report a problem".

## Government / financial / news / COVID declarations
- Not applicable.

## Play App Signing
- App signing key SHA-1: `2C:E6:30:A5:1C:FD:F5:9A:64:14:F1:4A:46:27:BA:A2:E5:FF:39:B7`
- App signing key SHA-256: `83:CF:73:77:29:89:46:38:CD:26:67:A7:3C:EF:F5:78:B9:89:AD:9C:BE:06:6A:F2:CE:9B:BC:41:97:CD:48:79`
- Play-installed builds are signed with this key, so Google Sign-In (Sheets export) needs an Android OAuth
  client with this SHA-1 and package `com.radovanbauer.ounce`.
