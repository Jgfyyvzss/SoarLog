# Proposed feature: Logbook endorsements

Nov 4, 2026 · @Peter

## Status and purpose

A draft for discussion, not yet adopted. Paper glider logbooks carry endorsements on pro-forma pages or inserts: winch launch, aerotow, self-launch, flight reviews and the like. This feature records each one in the app with a short type, a date and a picture of the page, and can have it witnessed with the existing witness sign-off.

The record stays minimal on purpose. The page itself holds the detail (who signed, what was covered, any conditions), so the app keeps a picture of it rather than copying its contents into fields.

Adding the pictures changes the backup: it becomes a zip holding the JSON and the images as separate files, and the witness photos move there too.

## The endorsement record

| Field | Rules |
| --- | --- |
| Type | Free text, required, non-blank, for example "Winch launch" or "Flight review". No fixed list. |
| Date | Required, YYYY-MM-DD, the date on the page. |
| Image | Optional. An endorsement can be entered first and photographed later, and shows "No image" until then. |

- **Not included (pilot's decisions, 2026-11-04):** no expiry or due date and no reminders, no link to a flight, no instructor fields (the instructor's name and number come only from a witness sign-off), and no CSV export.
- **Order:** newest first by date, as the Flights list is.

## The image

- **Images only:** from the phone's own camera app or the system photo picker. PDFs are not accepted (pilot's decision, 2026-11-04); a PDF can come in as a screenshot or a photo of the printed page, and PDF support could be added later without changing how images are stored.
- **No new permission:** the camera app writes to a file-provider address in the cache, as the witness photo already does. The photo picker (`ActivityResultContracts.PickVisualMedia`) needs no storage permission, and on phones without the system picker it falls back to the document picker. Its behaviour on API 26 to 29 is to be checked when building.
- **Processing:** decoded at a reduced sample size so a full camera photo is never held in memory, turned upright from its EXIF orientation (AndroidX ExifInterface, already in use), scaled to at most 1600 pixels on its longest side and re-encoded as JPEG at quality 85, falling back to 75 and 65 to stay within about 500 KB. Re-encoding writes no EXIF data, so location data is dropped. The camera's original is deleted at once.
- **Size:** an endorsement is usually under a third of an A5 page of print, which stays clearly readable at 1600 pixels and comes to roughly 100 to 300 KB. 1 MB is the hard limit (pilot's suggestion, 2026-11-04): an image still over it after the fallbacks is refused.
- **Replace and Remove:** the pilot can replace the image or remove it. A witnessed endorsement then shows Changed since witnessed, because the image is what was witnessed (see the seal below).
- **Viewing:** tapping the thumbnail opens the image full screen with pinch zoom.

## Storage on the phone

- **In the database:** the image is a BLOB in its own table, keyed by the endorsement, so lists never load image bytes and only the detail view, the PDF and the backup read them.
- **Row size:** Android reads rows through a cursor window of about 2 MB, and a row larger than that cannot be read back. The 1 MB limit keeps every image row well inside it.
- **Auto Backup:** the images stay in the database and so go to Android Auto Backup with the rest of the logbook, as the witness photos do (witness decision 3). Auto Backup stops backing up an app whose data passes 25 MB. Thirty endorsements at about 200 KB come to about 6 MB, which is well inside, but a very large collection could approach the limit. This is why the target is about 500 KB rather than the full 1 MB.

## Data model (schema version 4)

| Record | Fields | Rules |
| --- | --- | --- |
| endorsement (new table) | id (UUID), type (text), date (text YYYY-MM-DD), image\_sha256 (nullable), witness\_signoff\_id (nullable foreign key), witness\_seal (text, nullable), updated\_at, deleted\_at | Type non-blank. The two witness columns are set together or both null. Index on `date` and on `witness_signoff_id`. |
| endorsement\_image (new table) | endorsement\_id (primary key, foreign key), image (JPEG BLOB) | At most 1 MB, a JPEG, and its SHA-256 equals the endorsement's `image_sha256`, all kept in the repository rather than SQL, as for witness photos. Deleted with its endorsement, so a tombstone carries no image. |

- **Image and hash:** `image_sha256` set with no image row means the image was left out of the backup it was restored from; the detail view says so. `image_sha256` null means there is no image.
- **Migration:** 3 to 4 adds the two tables, with a `MigrationTestHelper` test.

## Witness sign-off for an endorsement

Uses the existing feature: the same Setting, screen, camera photo, self-witness check and quick-pick list. While Witness sign-off is off, nothing about it shows on endorsements.

- **One at a time:** an endorsement is witnessed on its own from its detail view; there is no batch, and a sign-off covers either flights or one endorsement, never both.
- **Seal:** the same canonical text rules as the flight seal (`key=value` lines with LF, the same escaping, SHA-256 in lower-case hex), with these lines in this order: `seal_version`, `record` (always `endorsement`, so a flight seal and an endorsement seal can never coincide), `type`, `date`, `image_sha256`, `witness_name`, `witness_identifier`, `signed_at`, `photo_sha256`. Endorsement seals are version 1 of their own list.
- **What it covers:** the image's hash, not its bytes. A backup without images keeps the seal valid, while replacing or removing the image shows Changed since witnessed.
- **Wording (draft, to be settled):** shown before the camera: "Your name, the number if given, the time of signing and a small photo of your face are kept with this endorsement in this logbook on this phone and in its backups. They show who witnessed it, and let the app notice a later change." Consent line: "I witnessed this endorsement and agree that my name, number and photo are stored with it in this logbook."
- **Clean-up:** a sign-off is deleted with its photo when no live flight and no live endorsement uses it. The existing clean-up queries gain the endorsement table.

## Screens

- **Where:** an Endorsements list, opened from Settings beside the pilot name and identifying numbers, since endorsements belong to the pilot rather than to a flight. To be confirmed (Still open).
- **List:** one row per endorsement: date, type, a small thumbnail or "No image", and the Witnessed, Changed or Cannot verify mark while Witness sign-off is on. A plus button adds one.
- **Detail:** type, date, the image with Take photo, Choose image, Replace and Remove, the witness card (as on the flight form) while the Setting is on, and Delete. Changing the type, date or image of an endorsement that still matches its seal first asks, as for flights.

## Backup: a zip (format version 3)

The JSON backup becomes a zip, because base64 images inside one JSON string held in memory do not scale to page images. Thirty images come to about 8 MB of text.

**Layout**

- `backup.json` first, compressed: the same JSON as now, with `format_version` 3, minus the base64 photo field, plus the `endorsements` array.
- `images/<sha256>.jpg` after it, one per distinct image, stored without compression since JPEGs do not shrink, in name order. Witness photos and endorsement images share the folder; the hash already in the JSON names the file, so the same image is stored once.
- File names change from `.json` to `.zip`, MIME type `application/zip`.

**JSON changes**

- **Witness sign-offs:** `photo` is dropped; `photo_sha256` and `photo_removed` stay, and the photo is the zip entry with that hash.
- **`endorsements`:** one object per endorsement with `id`, `type`, `date`, `image_sha256`, `witness_signoff_id`, `witness_seal`, `updated_at` and `deleted_at`, sorted by `id`.
- **Manifest:** `contains_photos` becomes `contains_images`, true when any witness photo or endorsement image is in the zip.

**Writing**

- Streamed with `ZipOutputStream` straight to the file the folder picker gives, reading images from the database one at a time, so memory stays small whatever the number of images.
- **Include images:** the Include witness photos switch becomes Include images (on by default, kept on this phone only) and covers both kinds. Off, the hashes and seals stay and the `images` folder is empty.
- The CSV overwrite's safety copy and Back up now write the same zip.

**Reading**

- The picked file is copied to the cache and opened with `ZipFile`, so `backup.json` is read and checked before any image, and then each image is read only when needed.
- **Limits, against a damaged or hostile file:** only `backup.json` and names of the form `images/<64 hex>.jpg` are read and anything else is ignored; nothing is extracted to a path built from an entry name; `backup.json` may be at most 50 MB, a witness photo 100 KB and an endorsement image 1 MB, each counted while reading rather than trusted from the zip header.
- **Image checks:** a file must be a JPEG within its limit and match its name's hash. A missing or bad image is reported and left out, and its record stays with the hash, as for witness photos today.
- **Stable output:** a round trip gives an identical `backup.json` apart from `exported_at`, and the same set of image files. The zip bytes themselves need not match.

**Old backups**

- Version 1 and 2 JSON files are no longer read (pilot's decision, 2026-11-04). A `.json` backup is refused with a message that names the last app version that restores it: install that version, restore, then update, and Room's migrations bring the database forward. The spec's rule that older versions are "upgraded step by step" is replaced by this.
- The base64 photo code is removed.
- **Folder size:** the backup folder keeps the last 10 files, each a full copy. With 6 MB of images that is about 60 MB in a folder that may be cloud-synced.

**Merge**

- Endorsements match by `id`, the newer `updated_at` wins, and tombstones are respected, as for every other table.
- An image left out of one side is taken from the other when the hashes match, as witness photos are now.
- A newer endorsement brings its own seal, which then shows whether it still matches.

## PDF export

- **Endorsements section:** after the last flight page, on the template's page size, a heading and then each live endorsement, oldest first, with its date, type, the witness's name and number when its seal still matches and Witness sign-off is on, and its image scaled to fit the width, as many as fit a page.
- **Filters:** the flight filter does not apply to endorsements; all live endorsements are included.
- **Choice:** an Include endorsements check box in the export dialog, on by default. To be confirmed (Still open).
- **Resolution:** images are drawn at about 150 dpi for the printed size, so each adds roughly its stored size to the PDF. Android's PdfDocument keeps finished pages in memory until the end, which is fine for tens of endorsements; the 10,000-flight test should be repeated with 30 endorsements.

## Changes to the main specification if adopted

- **Purpose and scope:** endorsements in scope. The "one JSON file is the only backup" line becomes one zip.
- **Data model and Appendix A:** the two tables, schema version 4.
- **Screens:** the Endorsements list and detail, and the Settings entry.
- **Backup and restore, Appendix B:** format version 3 as above, Include images, and old versions refused.
- **Templated PDF creation:** the Endorsements section and its check box.
- **Witness sign-off:** endorsements as a second thing to witness, and the clean-up rule.
- **Appendix F:** no new permission, plus the tests below.
- **Build order:** the steps below.

## Build plan

Each part with its tests; the pilot builds and runs them after each part. Part 1 stands alone and could go first.

1. **Zip backup (format 3):** witness photos move into the zip, Include images, old versions refused, the read limits. Tests: round trip with and without images, a photo shared by two sign-offs stored once, merge filling a missing image, refusal of a version 2 JSON file with the right message, and rejection of over-size entries, unknown names and hash mismatches.
2. **Core:** the endorsement seal with fixed test vectors, a flight seal and an endorsement seal with the same values differing, and the image rules (scaling, quality fallback, the 1 MB limit) as pure functions.
3. **Database (version 4):** the two tables, the migration test, the repository rules, and sign-off clean-up counting endorsements.
4. **Image:** camera and photo picker, upright, scaled, re-encoded, original deleted; no EXIF data in the result.
5. **Screens:** the list, the detail, the full-screen viewer, and the witness card and edit warning.
6. **Backup with endorsements:** the `endorsements` array, endorsement images in the zip, merge.
7. **PDF:** the Endorsements section, the check box, and a test that the section follows the last flight page and fits images to the page.
8. **Spec:** the changes above, recorded in `docs/spec.md`.

## Decisions (2026-11-04)

1. **Fields:** Type (free text), Date, optional Image. Most of the detail stays in the image.
2. **No expiry, no flight link, no instructor fields, no CSV.**
3. **PDF:** gains endorsements with their images.
4. **Instructor details:** only through the witness sign-off.
5. **Images only,** no PDFs, capped at 1 MB and stored in the database.
6. **Auto Backup:** images stay in it with the database.
7. **Backup:** a zip, with witness photos moved into it, and no support for older backup files.

## Still open

- Where the Endorsements list opens from: Settings beside the pilot profile (proposed), or a Home button.
- The Include endorsements check box in the PDF dialog, or always included.
- The witness wording for endorsements.
- The last app version that reads JSON backups, for the refusal message: set when the zip format ships.
- Whether to keep fewer than 10 backup files now that each is larger.
- Cropping: not proposed. The picture is framed when it is taken, or cropped beforehand in the phone's gallery app.

## Sources

To be checked when building, against official documentation:

- [Android: photo picker](https://developer.android.com/training/data-storage/shared/photopicker)
- [Android: Auto Backup for Apps](https://developer.android.com/identity/data/autobackup), for the 25 MB limit
- [Android: CursorWindow](https://developer.android.com/reference/android/database/CursorWindow), for the row-size limit
- [Java: java.util.zip](https://developer.android.com/reference/java/util/zip/package-summary)
