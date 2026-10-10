# Ounce

A simple, local-first calorie and macro tracker for Android. Snap a photo of your meal (or type what you ate)
and an AI model estimates the foods, portions, calories and macros. Fix anything with a tap or by chatting
("only half the rice", "no butter").

- **On-device AI by default.** Uses Gemini Nano through Android's ML Kit GenAI Prompt API on supported phones
  (e.g. Pixel 9+). Photos never leave the phone and it works offline.
- **Optional cloud AI with your own key.** Paste a Gemini API key for noticeably better estimates.
  The key is stored encrypted with the Android Keystore and excluded from backups.
- **No account, no server.** Your log lives in a local database and is included in Android's backup to your
  Google account (photos are not, to stay within Android's 25 MB backup limit).
- **Fast logging.** In-app camera with one-tap capture; analysis runs in the background. A text box on the home
  screen logs meals from a description. Launcher shortcuts for "Snap meal" and "Log by text".
- **Also:** portion quick-picks, offline food search over USDA FoodData Central (~13k foods), custom foods,
  weight tracking, daily goals, and weight/calorie charts.

## How accurate is it?
Photo calorie estimates are inherently approximate. On 150 dishes from the
[Nutrition5k](https://github.com/google-research-datasets/Nutrition5k) test set (weighed ground truth):

| Model | Calorie error (MAE, % of mean) | Within ±20% |
|---|---|---|
| Gemini Nano (on-device, Pixel 9) | ~48% | 29% |
| Gemini 3.8 Flash (cloud) | ~27% | 49% |

So on-device estimates are a starting point to review; with a cloud key, most meals land close.

## Building
See [BUILDING.md](BUILDING.md).

## Data
Nutrition data: [USDA FoodData Central](https://fdc.nal.usda.gov/) (SR Legacy and FNDDS), public domain.
