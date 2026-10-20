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
