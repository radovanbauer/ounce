# Draft answers for Play Console forms

## Data safety
- **Does your app collect or share any of the required user data types?** No.
  Rationale: Play defines "collected" as transmitted off the device by the app *to the developer or a third party*.
  Two things to judge carefully:
  - Optional cloud AI sends meal photos/text to the provider the user configured with their own key. This is
    user-initiated and to a service the user chose; many apps still declare it to be safe. **Conservative option:**
    declare *Photos and videos → Photos* and *App activity → Other user-generated content* as **Shared**, purpose
    *App functionality*, **optional**, **not collected by developer**, encrypted in transit: Yes.
  - Barcode numbers to Open Food Facts: not personal data.
- **Encrypted in transit:** Yes (HTTPS only).
- **Users can request data deletion:** data lives on the device; uninstalling deletes it.

## Health apps declaration
- Features: **Nutrition and weight management / food logging.** Not a medical device; no diagnosis or treatment.

## Content rating (IARC questionnaire)
- Category: Utility / productivity (reference/health). No violence, sexuality, gambling, controlled substances,
  user-to-user interaction or location sharing. Expected rating: Everyone / PEGI 3.

## Target audience
- 18+ (or 13+). Not designed for children. Not in the Families program.

## Ads
- Contains ads: **No**.

## App access
- All features available without login. (Google Sheets export needs a Google account, optional.)

## Generative AI
- The app uses generative AI to estimate nutrition. In-app reporting: meal details → "Report a problem with this
  estimate", and Settings → About → "Report a problem".

## Government / financial / news / COVID declarations
- Not applicable.
