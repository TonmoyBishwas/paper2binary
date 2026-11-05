# Paper2Binary

Turn stacks of paper survey forms into clean digital data using nothing but an Android phone.

People tick boxes on a half-A4 sheet. You point your phone at each sheet, it beeps, and you move
on to the next one. The answers end up as charts and an Excel-ready table. There is no server and
no account, and everything stays on the phone.

## How it works

1. **Create a survey** in the app: a title, 5 or 6 multiple-choice questions and an optional
   comment box. The app checks that it all fits on half an A4 page.
2. **Print one master sheet**, then print or photocopy it as many times as you need. Every copy is
   identical and all are read the same way, so there is no per-sheet printing. The form is pure
   black and white to keep printing cheap.
3. **Scan.** Hold the phone over a filled-in sheet. When it is steady, the app captures it, reads
   it and beeps. Swap the sheet and repeat. Sheets that are not clear are set aside for you to check.
4. **Analyse.** See a chart per question, compare two questions in a cross-tab, and export a ZIP
   containing `responses.csv`, `summary.csv` and the comment images.

## Filling in the form

- A **black pen** is ideal. Red and blue also work, because the scanner uses the darkest colour
  channel of the photo.
- A clear tick inside the box is enough. Filling the box in completely also counts.
- Keep the four corner squares and the QR code clean and uncut.

## What keeps the data right

| Risk | What the app does |
|---|---|
| A wrong answer saved silently | Every question gets a confidence check. Faint marks and double ticks go to a person, with the sheet photo beside them. |
| The same sheet counted twice | The next capture only happens after the current sheet has left the camera view. |
| A sheet rescanned later | All copies are identical, so the app compares the handwriting in ticks and comments, and asks before saving a match. |
| Crooked photos, shadows | Corner markers correct perspective and rotation (even upside down), and thresholds follow the local brightness. |
| Photocopy wear on the QR code | Strong error correction, a direct read at the known position, then a fallback to the current survey's layout. |

## Comments

Handwritten comments are cropped and stored as images. On-device recognition makes a first draft,
which you confirm or correct on the Comments screen. Treat the draft as a starting point: it works
best on neat handwriting in Latin script and does not read other scripts, so the image is always kept.

## Using several phones

- **Share the survey:** Survey screen > Share > *Send survey to another phone*, then import it from
  the home screen. A phone without the survey can still scan sheets, because the QR code holds the
  layout. It just shows generic labels until the survey file is imported.
- **Combine results:** *Send survey with all responses* from each phone, and import those files on
  one phone. Each response has a unique id, so importing a file twice is harmless. Imported
  responses carry their answers and comment text, but not their images.

## Project layout

```
core/   The scanning engine in plain Kotlin, with no Android dependency
  layout/    Where everything is printed; shared by the printer and the scanner
  vision/    Corner markers, perspective correction, QR code
  omr/       Deciding which boxes are ticked
  dedupe/    Handwriting fingerprint for spotting rescans
  pipeline/  Photo to answers, and the auto-capture gate
  analysis/  Statistics, CSV export, portable survey files
app/    The Android app: Compose UI, CameraX, Room, PDF rendering, ML Kit
```

## Building

Requires Android Studio (or JDK 17+ and the Android SDK).

```bash
./gradlew :core:test            # engine tests against simulated phone photos
./gradlew :app:assembleRelease  # app/build/outputs/apk/release/app-release.apk
./gradlew :app:installDebug     # install on a connected phone
```

The engine tests render forms, draw simulated pen ticks, apply perspective, shadows, blur and
noise, and then check the answers read back against the known ones.

The app needs Android 8.0 or newer. The release build is signed with the debug key so it can be
sideloaded directly. Set up your own signing key before publishing it to a store.

## Form format

The QR code on each sheet holds a short description such as `P2B1:K7M2QX:4.4.2.5.5.2:C`: the format
version, survey id, option count per question (`M` marks a multi-select question) and whether there
is a comment box. Box positions are computed from this, so changing the layout constants requires
a new format version, or sheets already printed would be read wrongly.
