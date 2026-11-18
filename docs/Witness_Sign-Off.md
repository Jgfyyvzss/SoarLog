# Optional feature: Witness sign-off for the glider logbook

Oct 20, 2026 · @Peter

## Status and purpose

This optional witness sign-off lets a pilot show that someone else confirmed a logbook entry. It was adopted on 2026-10-23 as build step 11 of the main specification (see Decisions and Build plan below). It aims for reasonable assurance that an entry was not self-witnessed, not for any legal standard.

The design suits an instructor who may sign only once, so there is no account, no PIN and nothing to remember. The witness types a name and number, is photographed by the phone's camera, and ticks a consent line. The app then seals the flight's signed fields together with those details, so a later edit to a signed field shows.

## How it works

The feature sits behind a Setting called Witness sign-off, which is off by default. Turning it off hides every witness screen and keeps any existing data.

1. The pilot opens a flight, or a day's group in the Flights list, and taps Witness. Each unwitnessed flight in the group is ticked and can be unticked.
2. The witness enters their name and, if they have it to hand, their licence or membership number, or taps a previous witness from a quick-pick list built from earlier sign-offs.
3. The phone's own camera app opens and the witness takes a photo of their face.
4. The witness ticks the consent line: "I witnessed these flights and agree my photo is stored with them in this logbook."
5. The app saves one sign-off and seals each ticked flight.

**Photo handling**

- The photo is taken through the system camera app, so the app does not declare the CAMERA permission and shows no permission prompt.
- The app scales the photo to 200 pixels on its longest side and re-encodes it as a JPEG at quality 70, which removes embedded location data. The result is about 10 to 20 KB, and the original is deleted at once.

**Checks**

- A witness whose name or number matches the pilot profile is rejected.
- On Student flights, the witness name is pre-filled from the PIC field when all ticked flights share it, with a warning if the witness is someone else.
- The name is required; the number is optional (decided 2026-10-23).
- The photo is required (decided 2026-10-23). It is the evidence of who witnessed; without it a sign-off is only a tick box.

## The seal

Each witnessed flight carries a SHA-256 seal that lets the app tell whether a signed field has changed since the witnessing.

**Signed fields.** The hash input is UTF-8 text with one `key=value` line per field, in this fixed order and empty for a null: `seal_version`, `log_no`, `date`, `duration_minutes`, `glider_id`, `airfield_id`, `launch_type_id`, `role`, `pic_name`, `launch_time`, `landing_time`, `landing_airfield_id`, `landing_place`, then `witness_name`, `witness_identifier`, `signed_at` (ISO 8601, UTC) and `photo_sha256` (always set, since a sign-off needs a photo; the line stays empty-capable so the seal text is unchanged). Notes, club, source, import batch and WeGlide data are not signed.

- **Exact text (as built, 2026-10-23):** each line is `key=value` followed by LF, the last line included, with no other whitespace. Numbers are plain decimal, `date` is YYYY-MM-DD, `role` is the stored name (for example STUDENT), and `signed_at` is ISO 8601 UTC as the JSON backup writes it (milliseconds shown only when not zero, for example `2026-01-03T00:00:00.250Z`). A backslash, CR or LF inside a value is written as `\\`, `\r` or `\n`. The seal is the SHA-256 of that text as UTF-8, in lower-case hex, and is compared ignoring case.
- **IDs:** the glider, airfield and launch type are signed by ID, so renaming a glider or airfield does not break a seal, while pointing the flight at a different one does.
- **Photo:** the seal covers the photo's hash, not its bytes, so removing the photo later keeps the seal valid.
- **Checking:** the seal is recomputed whenever a flight is shown and whenever a file is imported, and compared with the stored value. A mismatch shows "Changed since witnessed". That status is never stored.
- **Versions:** `seal_version` fixes the field list. A flight with a version the app does not know shows "cannot verify".
- **No secret:** the seal detects edits but cannot stop someone who recomputes it.

## Data model

One new table holds the sign-off events, and two nullable columns on the flight point to them. Types follow Appendix A of the main specification.

| Record | Fields | Rules |
| --- | --- | --- |
| witness\_signoff (new table) | id (UUID), witness\_name, witness\_identifier (nullable), signed\_at (epoch ms, UTC), photo (JPEG bytes, nullable), photo\_sha256 (nullable), photo\_removed, seal\_version, consent\_confirmed, updated\_at, deleted\_at | The name is required and non-blank; the identifier is optional, null when not given. `consent_confirmed` must be true. A photo is required when signing, so `photo_sha256` is always set; `photo` becomes null only through Remove photo. Name and identifier are never edited after signing, so a correction means deleting the sign-off and witnessing again. The photo is at most 100 KB. |
| flight (two added columns) | witness\_signoff\_id (nullable foreign key), witness\_seal (text, nullable) | Both are set together or both are null. Index on `witness_signoff_id`. |
| Setting | witness\_signoff (boolean) | Default off, and backed up with the other settings. |

- **Batches:** one sign-off can cover many flights, such as a day's flying. The seal is per flight.
- **Quick-pick list:** it is the distinct name and identifier pairs of live sign-offs, so there is no separate witness table.
- **Storage:** the photo sits in the sign-off row as a BLOB. At 10 to 20 KB per sign-off, no separate file storage is needed.

## Display, editing and removal

- **Flights list:** a small Witnessed mark on sealed flights and a Changed mark where the seal no longer matches. A Witness filter offers any, witnessed, not witnessed and changed since witnessed.
- **Flight detail:** the witness name and identifier, the signing date (with "witnessed N days after the flight" when later than the flight), the photo thumbnail, and the seal status.
- **Editing:** changing a signed field of a witnessed flight first asks "This flight was witnessed by X. Changing it will mark it as changed since witnessed." Notes and club can be edited freely. The pilot can witness the flight again afterwards, which creates a new sign-off.
- **Remove photo:** an action on a sign-off that deletes the photo bytes and keeps the name, the number and the seals valid.
- **Remove witness mark:** the pilot can detach the witness from a flight after a confirmation. A sign-off that no live flight refers to is deleted with its photo.
- **Deleting a flight:** it follows the normal rules, including the same clean-up of unreferenced sign-offs.
- **Setting off:** the witness screens are hidden, nothing is deleted, and seals are not recomputed until the Setting is turned on again.

## JSON export and import

Witness data travels in the existing JSON backup as one new array plus two fields on each witnessed flight.

**Export**

- **`witness_signoffs`:** one object per sign-off with `id`, `witness_name`, `witness_identifier`, `signed_at`, `photo` (base64 JPEG, or null), `photo_sha256`, `photo_removed`, `seal_version`, `consent_confirmed`, `updated_at` and `deleted_at`. The array is sorted by `id` like the others.
- **Flights:** each flight object gains `witness_signoff_id` and `witness_seal`, null when the flight is not witnessed. The `settings` object gains `witness_signoff`.
- **Round trip:** the stored JPEG bytes are written unchanged with stable base64, so export, import and export still produce identical files.
- **Size:** a 200 pixel JPEG of 10 to 20 KB becomes about 14 to 27 KB in base64. Sign-offs are per batch, so hundreds add only a few MB.
- **Photo choice:** an Include witness photos option at export, on by default for backups to the pilot's own folder. Without photos the names, numbers, hashes and seals remain and the photo field is null. The manifest records `contains_photos`, so a file carrying faces is recognisable.

**Import**

- **Photo checks:** the photo must be valid base64, a JPEG, at most 100 KB, and must match its `photo_sha256`. A bad photo is dropped and reported, and the sign-off stays.
- **Seals:** every seal is recomputed and the summary reports, for example, "12 witnessed flights verified, 0 changed". A mismatch never blocks the import, and the flight simply shows Changed since witnessed.
- **Merge:** sign-offs match by `id` and the newer `updated_at` wins. If the signed fields of a flight differ between the two sides, the newer flight wins and its seal shows honestly whether it still matches.
- **Dangling reference:** a flight pointing at a sign-off that is not in the file keeps its data, loses the two witness fields, and is reported.
- **Unknown versions:** a flight with an unknown `seal_version` keeps its seal and shows "cannot verify".
- **Format version:** the new fields are optional and unknown fields are ignored. If the main specification has not shipped, they join format version 1. If it has, bump the version, because an older app would drop witness data on a round trip.

## CSV, WeGlide and Auto Backup

- **Flights CSV:** the export can include read-only `witnessed_by` and `witnessed_on` columns. Import ignores them and never creates a witness mark, so a spreadsheet cannot be used to self-witness, and every imported flight starts unwitnessed.
- **Glider and airfield CSV:** unchanged.
- **WeGlide and IGC import:** unchanged. Witness details and photos are never sent anywhere.
- **Android Auto Backup:** the main specification leaves Auto Backup at the Android default, which copies the app's database to the user's Google account backup. Photos stored in the database would be copied with it. Keeping them out means storing the photos as files in a directory that the backup rules exclude. This is listed as an open question below.

## Privacy and consent

A face photo identifies the witness, so the feature is opt-in and designed to keep the photo small, local and removable. Privacy rules differ by country and by whether the app is used personally or by a club, so check the rules where you live. This is not legal advice.

- **Consent:** the consent line must be ticked for every sign-off, and the confirmation is saved with it. The screen should show the witness exactly what is stored and why before the camera opens.
- **Local only:** the app never uploads the photo. It lives in the database, in the pilot's own JSON backups, and possibly in Android's Auto Backup.
- **Minimal data:** 200 pixels on the longest side, re-encoded so that location data is dropped.
- **Removal:** the pilot can delete a photo at any time, for example at the witness's request, keeping the name, the number and the seals.
- **Sharing:** the export choice allows a file without photos, and the manifest marks files that contain them.

## What it does not give you

The feature records that someone was photographed at the phone and gave a name, a number and a time, and it detects casual edits afterwards. It does not prove who the witness was.

- **No identification:** the app does not check that the face, name and number belong together, or to a real instructor.
- **Staged witnesses:** a pilot can photograph a friend and type any name. A reviewer who knows the pilot may notice a photo of the pilot instead.
- **No secret in the seal:** someone who edits a JSON file and recomputes the hashes can forge a witnessed flight. In the app, an edited flight is flagged, but the pilot can witness it again.
- **No trusted clock:** the signing time comes from the phone, which the pilot controls.
- **No legal standard:** none is claimed or needed. MyFlightbook's own documentation makes the same point about its signatures, saying a made-up instructor account is no different from signing your own logbook.

## If adopted: changes and build step

Adopting the feature touches seven parts of the main specification and adds one build step.

- **Data model and Appendix A:** the `witness_signoff` table, the two flight columns and the Setting.
- **Appendix B:** the `witness_signoffs` array, the flight fields, the Setting, `contains_photos` and the Include witness photos choice.
- **Appendix C:** read-only `witnessed_by` and `witnessed_on` columns in the flights export, ignored on import.
- **Screens:** the Witness action, the marks and filter in the Flights list, the detail view and the Setting.
- **Appendix D and F:** no new permission, because the photo comes from the system camera app, plus the seal tests below.
- **Decisions:** the privacy points above.
- **Build order:** one new step, after the JSON backup and CSV steps, because it extends both.

**Build step: witness sign-off.** Done when:

1. The Setting shows and hides every witness screen without deleting data.
2. A batch sign-off creates one sign-off and seals each ticked flight.
3. The stored photo is at most 25 KB and carries no location data.
4. A seal verifies, and it flags an edit to a signed field but not to notes or club. Tests use fixed test vectors for the canonical text.
5. A witness matching the pilot's profile name or number is rejected, and Student flights pre-fill the witness from the PIC field.
6. A JSON export, import and export round trip is identical, including photos, and a photo-free export keeps names, hashes and seals.
7. Import drops a bad photo, reports the seal summary, and never blocks on a mismatch.
8. CSV import never creates a witness mark.
9. Remove photo and Remove witness mark work, and unreferenced sign-offs are cleaned up.

## Decisions (2026-10-23)

1. **Which flights:** any flight can be witnessed, singly or as a whole day's group from the Flights list.
2. **Witness number:** optional. The name is required. The self-witness check compares the name, and the number when given.
3. **Auto Backup:** photos stay in the database as designed. Anyone who wants nothing on Google turns Auto Backup off, which covers the whole logbook anyway, and many pilots keep their JSON backups on Google Drive. Keeping photos as separate files would add code and edge cases for no real gain (for example, an Auto Backup restore that brings back sign-offs without their photos).
4. **Consent wording:** minimalist and generic. Settled with the pilot when the screen was built; the text is under Still open below.
5. **Photo size:** 200 pixels on the longest side is fine, and smaller is probably adequate too. Confirm on a phone.
6. **Drawn signature:** left out.
7. **Backup format:** version 2. The app still reads version 1 files and upgrades them; an older app refuses a version 2 file instead of silently dropping witness data.
8. **Photo required:** a sign-off cannot be saved without a photo of the witness, because the photo is the evidence. Remove photo stays, for a witness who later asks for it to be deleted; the seal keeps the photo's hash.

## Review against the built app (2026-10-23)

- **Schema:** the database is at version 2, so this is version 3, with a migration and a `MigrationTestHelper` test. The migration drops the partial indexes first, as every migration does, and the open callback recreates them.
- **Changed since witnessed filter:** the seal is a SHA-256 over several fields, so SQL cannot test it. The app works out the set of changed flight IDs in Kotlin and the filter passes them as `f.id IN (...)`; at 10,000 flights this is quick. The Witness filter joins the shared filter, so Totals follow it too.
- **Camera:** the photo comes from the system camera app (`ActivityResultContracts.TakePicture` with a FileProvider address), so no CAMERA permission is declared. Older phones record which way up a photo is in its EXIF data instead of rotating the pixels, so the AndroidX ExifInterface library is needed to rotate it before scaling. Its version is to be checked when building.
- **CSV:** the flights import warns about columns it does not use. `witnessed_by` and `witnessed_on` should be ignored silently instead.
- **PDF:** the GFA-style template's Trainers Signature column shows the witness name and number (decided 2026-10-23, built with part 6).

## Build plan

The work goes in this order, each part with its tests, and the pilot builds and runs them after each part.

1. **Seal and checks (core):** built 2026-10-23 on the `Witness` branch (`core/witness`, tested by `WitnessTest`). the canonical text and SHA-256 seal with fixed test vectors, verification and the "cannot verify" case for unknown versions, the self-witness check against the profile, and the Student pre-fill from the PIC field.
2. **Database (version 3):** built 2026-10-23 on the `Witness` branch (`WitnessSignoffEntity`, `WitnessDao`, `MIGRATION_2_3`, `WitnessRepository`; tested by `MigrationTest` and `WitnessRepositoryTest` on a device). The rules Room cannot express (a name, consent, a photo of at most 100 KB, and both flight columns set together) are kept in `WitnessRepository` rather than SQL triggers, because the migration test builds older databases with the current triggers. Editing a flight keeps its witness mark, so an edited signed field shows as Changed. A sign-off no live flight uses is deleted, with its photo, after a mark is removed, a flight is deleted or witnessed again, or an import is undone. Until part 5, the JSON backup does not yet carry witness data. the `witness_signoff` table, the two flight columns with their index and foreign key, the Setting, and the migration test. Repository operations: sign a batch, remove a photo, remove a witness mark, and clean up sign-offs that no live flight refers to, including when a flight is deleted or an import is undone.
3. **Photo:** built 2026-10-23 on the `Witness` branch (`WitnessPhotoRules` in core, `WitnessPhoto` in data, AndroidX ExifInterface 1.4.2; tested by `WitnessPhotoRulesTest` and, on a device, `WitnessPhotoTest`). The camera app writes into a private cache file shared through the app's file provider. The photo is decoded at a reduced sample size, so a full-size photo is never held in memory, then turned upright for any of the eight EXIF orientations. If quality 70 is over 25 KB, 55 and then 40 are tried. Re-encoding from pixels writes no EXIF data at all. take the picture with the system camera into a temporary file, rotate it the right way up, scale it to 200 pixels on its longest side, re-encode it as JPEG at quality 70, and delete the original. The stored photo is at most 25 KB and carries no location data.
4. **Screens:** built 2026-10-23 on the `Witness` branch (`ui/witness/WitnessScreens.kt`, strings in `strings_witness.xml`; the filter SQL is tested by `FilterAndTotalsTest`). The Setting sits under Flight form. The sign-off screen opens from a day header in the Flights list or from the witness card on the flight form; Save needs a photo and the consent tick. The edit warning comes when Save is tapped, and only when a signed field would actually change. The Witness filter and its marks show while the Setting is on, or while a Witness filter is still set. The Flights list and Totals refresh the changed set whenever flights or sign-offs change. Planned: the Setting; the Witness action on a flight and on a day's header; the sign-off screen (flights ticked, name, optional number, quick-pick list, what is stored and why, the camera, the consent tick); the Witnessed and Changed marks in the Flights list; the Witness filter; the witness card on the flight form; the warning before editing a signed field; Remove photo and Remove witness mark.
5. **JSON backup version 2:** built 2026-10-23 on the `Witness` branch (`core/backup/BackupWitness.kt`; tested by `BackupWitnessTest` and `BackupCodecTest`, and on a device by `BackupServiceTest`). `contains_photos` sits after `app_version`, `witness_signoffs` after `flights`, and the two flight fields after `deleted_at`. The 1 to 2 upgrade only sets the version. Include witness photos is a switch in the Backup section, shown while the Setting is on, on by default, and kept on this phone only (not in the file), because the only export is the backup to the pilot's own folder. A sign-off, or any of its times, without a name or consent refuses the file like any other validation problem; bad photos and dangling references are repaired and listed in the Restore or Merge dialog with the seal summary. Merge keeps a photo that the other file left out (same hash, not removed), so merging a photo-less backup never loses photos. After rows go in, a sign-off no live flight uses is deleted, as elsewhere. Planned: the `witness_signoffs` array, the two flight fields, the Setting, `contains_photos` and the Include witness photos choice. Import checks the photos, reports a seal summary, handles dangling references, and Merge matches sign-offs by ID. The version 1 to 2 upgrade, and the round trip with and without photos.
6. **CSV and PDF:** built 2026-10-23 on the `Witness` branch (tested by `CsvTest` and `LogbookPdfTest`; the CSV columns and the GFA-style PDF checked on the phone 2026-10-27). The two columns follow `notes` and are written only while the Setting is on. `witnessed_by` is the name with the number in brackets, and `witnessed_on` the signing date (YYYY-MM-DD, phone's time zone). Both are filled only for a flight whose seal still matches, so neither the CSV nor the PDF claims a witness for a flight changed since. The PDF gains a `witness` column field, used for GFA-style's Trainers Signature; it prints the name and number in the same cases and is otherwise empty, for a hand signature. Planned: read-only `witnessed_by` and `witnessed_on` in the flights export, ignored silently on import, which never creates a witness mark.
7. **Spec:** done 2026-10-23. `docs/spec.md` now has a Witness sign-off section, the scope line, the data model row, the Flights list marks, filter and day-header button, the Settings entries, the backup contents and manifest, Appendix A (schema version 3 columns and table, the two settings), Appendix B (version 2, the example, import checks and merge), Appendix C (the two columns), Appendix F (no CAMERA permission, the witness tests) and the build-order status. The GFA-style Trainers Signature column and the template `witness` field went in with part 6.

## Still open

- ~~The consent wording (decision 4)~~ settled 2026-10-23: "I witnessed these flights and agree that my name, number and photo are stored with them in this logbook." Shown before the camera: "Your name, the number if given, the time of signing and a small photo of your face are kept with these flights in this logbook on this phone and in its backups. They show who witnessed the flights, and let the app notice a later change."

## Sources

Pages consulted on 2026-10-20; several were read as search extracts, not in full.

- [MyFlightbook: instructor signatures](https://myflightbook.com/logbook/mvc/pub/CFISigs)
- [ForeFlight: endorsing a logbook](https://support.foreflight.com/hc/en-us/articles/216184638)
- [ForeFlight: signatures lock entries](https://blog.foreflight.com/2016/06/03/6-reasons-to-go-digital-with-foreflights-integrated-logbook/)
- [LogTen: signing entries](https://intercom.help/pilotbase/en/articles/11625726-getting-my-logbook-and-endorsements-signed-in-logten)
- [Android: minimize permission requests](https://developer.android.com/privacy-and-security/minimize-permission-requests)
