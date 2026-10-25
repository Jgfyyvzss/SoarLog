# Android Glider Logbook: Specification

Oct 18, 2026 · @Peter

## Purpose and scope

This is a personal glider flight log for Android that records what was flown, where, when and for how long. Data lives on the device, is exportable in open formats, and must stay responsive with 10,000 flights.

**In scope**

- Form-based flight entry, manual or pre-filled from an IGC file.
- User-maintained airfield, glider and club records, and a pilot profile (name and licence or membership numbers).
- Flexible filtering, personal totals by role, and an aircraft-logbook calculator.
- CSV export and import, and full JSON backup and restore.
- Optional witness sign-off: a witness confirms flights with a name, an optional number and a photo, and later edits to a signed field show (added 2026-10-23).

**Out of scope**

- Computed aircraft airframe hours or launch counts. A personal log can't know them for club, loaned or syndicate gliders. The glider record holds only the last total the pilot wrote in the aircraft logbook, as a calculator default.
- iOS, SQLite-file backups, and any Auto Backup configuration (left at the Android default).
- Required launch and landing times. A flight records its date and duration. Launch and landing times, and the landing place, are optional fields behind two Settings that are off by default.

## Platform and stack

The app is Android-only: native Kotlin with Jetpack Compose and a Room (SQLite) database. A full Kotlin Multiplatform project is rejected because of its extra build and learning cost.

- **Port-friendly choices that cost nothing:** Room, which is itself multiplatform-capable since 2.7.0, and Compose, which Compose Multiplatform builds on.
- **Core logic in its own package:** The IGC parser, duration arithmetic and CSV and JSON handling use no `android.*` imports, so they are testable on a plain JVM.
- **HTTP behind one interface:** There are only a few calls (WeGlide, later OGN), so use whichever client is familiar.
- **Performance:** Indexes on date, airfield, glider and `log_no`, with filtering, sorting and totals done in SQL, and a paged list. 10,000 rows is small for SQLite.
- **Data portability:** A documented, versioned JSON schema is the main aid to anyone porting to iOS later.

## Data model

The model is normalised: flights refer to gliders, airfields and clubs by ID, and every record has a UUID and an `updated_at`.

| Table | Fields | Rules |
| --- | --- | --- |
| Flight | log\_no, date, duration\_minutes, glider, airfield, club, launch\_type, role, pic\_name, notes, source, import\_batch\_id, plus optional launch\_time, landing\_time, landing\_airfield and landing\_place | `log_no` is required, unique and positive. `date` is the local flight date. Duration is whole minutes. Role is one of PIC, STUDENT, INSTRUCTOR, COACH, MUTUAL. Launch type is mandatory. Club is stored whenever the airfield has at least one club. The optional fields appear in the form only when their Settings are on. Times are local 24-hour HH:MM, and a landing time must be after the launch time. A landing airfield and a landing place are never both set, and neither set means the same place as departure. The pic\_name is required, non-blank text. |
| Glider | registration, type, weglide\_aircraft\_id, logbook\_total\_minutes, logbook\_total\_flights, logbook\_total\_date, incomplete, archived | Registration is required and unique, stored as written; matching ignores case, spaces and hyphens, and no format is enforced (`?` and `B4` exist). Type is free text, as the paper logbook shows it. The WeGlide aircraft ID is set per glider by the pilot from WeGlide's aircraft list, and WeGlide upload is blocked until it is set. Choosing an entry also replaces the type with WeGlide's name; with no pick, the original type stays. No make or class. The logbook total fields hold only the last total the pilot wrote in the aircraft logbook, as a default for the calculator, never a computed airframe total. |
| Airfield | name, latitude, longitude, match radius, default club, default launch type, incomplete, archived | Name is required and unique ignoring case. Coordinates are optional but needed for IGC matching. The match radius has a default and can be set per airfield. The default club, if set, is one of the airfield's own clubs, and both defaults only pre-fill the flight form. |
| Club, airfield-club link | club name; airfield and club IDs | Many-to-many, because an airfield can host several clubs and a pilot can fly with several at one field. |
| Launch type | name | Seeded with Aero, Winch, Self and Auto, and extendable. CSV values must match a name. |
| Opening balance | role bucket, as-of date, minutes, flights | One per role, entered once from the last paper page. |
| Witness sign-off | witness name, optional number, signing time, photo, photo hash, seal version, consent; and on each witnessed flight the sign-off and its seal | One sign-off covers a batch of flights, each with its own seal. The name and photo are required when signing; the photo can be removed later and its hash kept. See Witness sign-off. |

Deleted records are kept as tombstones so a JSON Merge respects deletions, and the unique index on `log_no` ignores them. Non-secret settings are part of the JSON backup. WeGlide credentials live in encrypted storage and are never exported.

## Flight entry, roles and log numbers

The flight form has dropdowns for glider, airfield and launch type, a role choice, a duration and notes. Every flight lands in exactly one role bucket, so Combined is the sum of all five.

- **Role:** PIC, or Dual with a second choice of Student, Instructor, Coach or Mutual.
- **Duration:** typed as digits on a numeric keyboard, the last two being minutes, so 51 is 0:51 and 324 is 3:24. H:MM with a colon is also accepted. Overflow is accepted (175, or 1:75, becomes 2:15), the field shows the result as it is typed (for example "= 3:24"), and the value is stored as whole minutes.
- **Club:** the dropdown appears only when the chosen airfield has more than one club, starting at the airfield's default club if one is set. With exactly one, it is set silently.
- **Launch type:** choosing an airfield fills the launch type with the airfield's default launch type, unless the pilot has already picked one. Editing an existing flight never changes its launch type this way.
- **Dropdowns:** glider rows read like `FQM, Hornet`, because several types repeat and only the registration tells them apart.
- **Adding from the form:** the glider and airfield dropdowns start with New glider and New airfield. New glider asks for the registration and type, and New airfield for the name only. The record is saved and selected without leaving the form, and an airfield created this way is incomplete until its coordinates are added.
- **Default glider (added 2026-10-25, built, no automated tests):** a "Default for new flights" check box on the glider screen. At most one glider is the default: ticking it on one glider clears it from any other, and archiving or deleting that glider clears it too. A new flight entered by hand starts with the default glider, which the pilot can change. An IGC import keeps its own glider matching, and when nothing matches it leaves the glider for the pilot to choose rather than using the default, because the logger may have flown in another glider. CSV import and editing an existing flight never use it. The Gliders list shows a Default badge on that glider. Stored as the setting `default_glider_id` and backed up. From TestNotes, 2026-10-25.
- **Default airfield (added 2026-10-25, built, no automated tests):** the same check box on the airfield screen, with the same rules: one airfield at most, cleared by archiving, a Default badge in the Airfields list, and used only for a new flight entered by hand, never by IGC or CSV import. The airfield then brings its own default club (or its only club) and default launch type, so a new flight opens with glider, airfield, club and launch type filled in. Stored as the setting `default_airfield_id` and backed up.
**Pilot in command**

Every flight records who was pilot in command, as required text.

- **Default:** the name from the pilot profile when the role is PIC, Instructor or Coach, and the pilot can overwrite it.
- **Student and Mutual:** the field starts blank, and another person's name must be entered before the flight can be saved.
- **Changing the role:** switching to Student or Mutual clears the field if it still holds the profile name, and switching to PIC, Instructor or Coach fills a blank field with the profile name. A name the pilot typed is never replaced.
- **No profile name yet:** the field is blank and the name is typed on each flight. The app never offers to save it to the profile.
- **History:** the name is copied onto the flight when it is saved, so later changes to the profile do not alter past flights.

**Optional fields**

Two Settings, both off by default, add optional fields to the flight form. Turning one off hides its fields and keeps any data already entered, and neither Setting makes its fields required.

- **Record landing place:** a Landing place choice that defaults to Same as departure, with an airfield dropdown and an Other place text box for outlandings. Regulators such as EASA and the FAA expect a pilot's logbook to show where each flight ended.
- **Record launch and landing times:** two local 24-hour time fields, typed as three or four digits on a numeric keyboard (905 is 09:05, 1305 is 13:05) or as H:MM, and stored as HH:MM. When both are entered, the duration is pre-filled from them and can still be edited, and the form warns if the two disagree by more than a minute.

**Log numbers**

- `log_no` is one continuous sequence matching the paper logbook, required and never blank.
- The form pre-fills the highest existing number plus one, read with an indexed `MAX` query, never from a stored counter. The first ever flight pre-fills 1 and the pilot types the real number once.
- Duplicates are blocked with a message naming the flight that holds the number. Deleting the highest-numbered flight frees its number.
- To swap or correct numbers, give one flight the next free higher number, fix the other, then renumber the first.

## Pilot profile

The pilot's name and identifying numbers are recorded once, in Settings, and are saved in the JSON backup. The name is the default pilot in command on new flights. The profile changes only when the pilot edits it deliberately in Settings. No flight entry, import, Merge, upload or other process alters it, and only a Restore into an empty install loads it from a backup.

- **Name:** free text.
- **Identifiers:** any number of label and value pairs, for example Licence number, Membership number or ARN. Labels are free text with a few suggestions, so the app is not tied to one country's scheme.
- **Kept apart from WeGlide:** the WeGlide ID and date of birth stay in encrypted storage, are not part of the profile, and are never exported.
- **Privacy:** the profile stays on the device and is written only to the pilot's own backups. It is not sent anywhere.

## Screens, filters and totals

The home screen offers New flight, From IGC, From logger, Upload to WeGlide and the aircraft logbook calculator immediately, and navigation covers Flights, Totals, Airfields and Gliders, with Settings behind a top-bar icon. Every screen is a scrolling list or a form, apart from the Totals grid, and the aircraft logbook calculator is a pop-up sheet over the Flights list, the Home screen or a glider's detail.

- **Home:** New flight, From IGC, From logger, Upload to WeGlide and Aircraft logbook buttons, a compact totals card, a By aircraft type card, and a "last backed up" line that nudges when stale. IGC files shared from other apps open the same import flow. The By aircraft type card shows hours and flights for the three types with the most hours. It is unfiltered and leaves out opening balances, which are kept by role, not by type. Gliders with a WeGlide aircraft are grouped by it and named by WeGlide; otherwise the type text is matched ignoring case (added 2026-10-23).
- **Flights:** newest first by date then `log_no`. Each flight row keeps its key items in fixed places: log number, date and role on the left of the first line with the duration at its right edge (320 · 1/9/26 · PIC … 1:26), then glider and type, airfield and launch type (FQM Hornet · YMBT · Winch), then the start of the notes on a third line when there are any, with a Witnessed, Changed or Cannot verify mark at the end of the second line while Witness sign-off is on. Each line is cut short rather than wrapped (changed 2026-10-23). Tapping a row opens the flight to edit, including notes, and the list is paged. Active filters show as chips above the list, and the filter button opens a bottom sheet with glider, airfield and club lists that each take several choices, plus date range, roles (any number), and a log-number range, a launch time range when the Times setting is on, and a Witness choice (any, witnessed, not witnessed, changed since witnessed) when Witness sign-off is on. While Witness sign-off is on, each day header also has a Witness button.
- **Totals:** a small grid, the one table-like screen, with a row each for PIC, Student, Instructor, Coach, Mutual and Combined and columns for hours and flights. With no filter active, each figure equals the opening balance plus the flights. With any filter active, opening balances are left out and only matching flights count, so "my hours in this glider at this airfield" is one query. They are labelled as the pilot's own time, never aircraft time. The Flights list and Totals share one filter, shown as chips on both screens, and it resets when the app is closed. When Instructor and Coach count toward PIC, their opening balances do too. The Home totals card shows PIC and Combined, always unfiltered and including opening balances.
- **Airfields and Gliders:** plain lists. A glider row shows the registration and type, a glider's detail also shows its logbook total as last entered, and an airfield row shows its name and club names. A plus button creates a record, tapping a row edits or archives it, and an "incomplete" badge marks records created by CSV import.
- **Settings:** pilot name and identifying numbers, Record landing place, Record launch and landing times, Witness sign-off, hours display (H:MM or decimal), whether Instructor and Coach flights also count toward PIC in Totals, opening balances, backup folder, the automatic backup schedule, Include witness photos (shown while Witness sign-off is on), Back up now, Restore and Merge, Spreadsheets (CSV export, import and undo last import), the WeGlide aircraft list with its Update button, whether WeGlide credentials are saved (with Remove), and the Clubs and Launch types lists.

## Aircraft logbook calculator

The calculator adds a day's flying to the aircraft logbook total, for the legal aircraft logbook. It never touches the database totals. The glider record keeps the last logbook total the pilot wrote, as a default only, because others may fly the aircraft and the figure can drift from the real logbook.

1. The pilot confirms the day's flights.
2. In the Flights list, each day has a header row with an "Aircraft logbook" button. Flights stay in date and log number order, because a day's gliders often alternate. With one glider flown that day the calculator opens straight away; with several, the pilot picks the glider first. The calculator includes all that glider's live flights for the day, even ones the active filter hides, and the pilot can untick any.
3. The calculator opens with the day's total hours as H:MM and the number of flights, both prefilled and editable, with a checklist of the included flights that updates both figures. The figures form a small table, like the sum on paper, with columns Flights (optional) and Hours and rows Day, Other pilots, Last entry and Total (changed 2026-10-23).
4. The logbook total fields are prefilled from the glider's stored values, with the date they apply to shown beside them. The pilot overwrites them when they differ from the aircraft logbook, for example after someone else flew the glider, and the calculator shows the new total.
5. For flying the app doesn't know about, such as other pilots' flights, the pilot fills in the Other pilots row. The same calculator opens from the Gliders screen with the stored total and no flights, for fully manual use.
6. The Home screen's Aircraft logbook button asks for the glider (those flown today first) and opens the calculator for today with that glider's flights, or the manual calculator if it did not fly today, so a pilot can do the day's paperwork for gliders they did not fly (added 2026-10-23).

**Saving the total**

- **Save new total:** this button writes the result back to the glider record, with the date of the flying day just added. In manual use the pilot picks the date, defaulting to today. Closing the calculator without saving changes nothing.
- **Double-add warning:** if the stored date is on or after the day being added, the calculator warns that the day may already have been added, because adding the same day twice is the easiest way to corrupt the figure.
- **Gliders screen:** a glider's detail shows the stored figures, for example "Logbook total as last entered: 1234:56, 5678 flights, 18/10/26", and lets the pilot edit or clear them by hand. The flights count is optional and may be removed later.

The count is labelled "Flights", as everywhere in the app, because a flight is one landing. Role does not matter here, since airframe time counts for every role. Hours can be shown as H:MM or decimal, selectable in Settings. H:MM is exact to the minute, and decimal hours are shown to one place, for example 4.5. The calculator's hours fields accept digits with the last two as minutes (123456 is 1234:56), H:MM, or decimal hours (1234.9), so airframe totals past 10,000 hours fit, and results follow the Settings choice. When the glider has no stored flight count, the new total's count stays blank unless a before count is entered.

## IGC import

An IGC file pre-fills the flight form. It is read from the file picker, a share action or a logger download, and kept for a while in the list of received files (see WeGlide upload) so the same bytes can be uploaded to WeGlide later. The logbook is not an archive of IGC files, and no flight is linked to its file.

**Extraction**

- **Flying detection:** ground speed is derived from consecutive fixes, and flying begins when it stays above 50 km/h for a few seconds, the threshold Flightbook is reported to use. A height gain above the starting altitude is also required, so a logger left in a car is not a flight.
- **Several segments:** a file with more than one flying segment lists them, and each can be imported as its own flight.
- **Preview:** launch and landing are shown as read-only text for a sanity check, and are stored only when the Times setting is on.
- **Fields produced:** flight date, duration, airfield, glider, and the next log number, plus launch and landing times when the Times setting is on and the landing place when the Place setting is on. Launch type and role are left to the pilot, since both are mandatory.

**Airfield matching**

1. Take the launch fix and compute its distance to every airfield that has coordinates.
2. Pick the nearest within that airfield's match radius.
3. If several are in range, preselect the nearest and list the others with distances.
4. If none match, prompt "No airfield found near this launch point. Create one?" with the form pre-filled with the fix's coordinates and elevation. Cancelling leaves the field blank, and the form can't be saved until an existing airfield is chosen.

**Glider matching:** the registration in the header is matched against glider records, ignoring case, spaces and hyphens, and then without its nationality prefix (Appendix D). The type text is free text and is not used.

The threshold and default radius should be tuned against a handful of the pilot's own IGC files.

## Timezones and the flight date

Times are stored as local clock times, so the only use for a timezone is converting an IGC file's UTC times into the correct local flight date and, when the Times setting is on, local launch and landing times. In Sydney, a flight launching before 11:00 in summer carries the previous UTC date.

Confirmed: drop the zone from airfields and flights entirely, and use the device's timezone for that one conversion. The form shows the date for the pilot to confirm or edit. The only failure case is importing a trip flight from another zone near midnight UTC, which is a one-tap date edit.

This also removes the scroll-and-search zone picker, the LatLongToTimezone dependency, and the country and zone data.

## LXNAV flight download

**Status:** the protocol is proven on an S100 (firmware 9.27, serial 33418, advertising as `LXNAV-S33418`) from a Linux PC, using a Python script over a raw RFCOMM socket. Nothing has been tried from Android, and the Nano, Nano3 and Nano4 are untested. The script, `lxnav_dl.py`, is kept in the repository under `tools/` and is the executable reference for everything below.

**Goal:** download an IGC flight log from an LXNAV logger over Bluetooth, feed it into the IGC import flow, and optionally upload it to WeGlide, so the pilot needs neither a cable nor a separate app to move the file. The app analyses the file itself to pre-fill the form, and WeGlide receives the same bytes for its own analysis.

1. The pilot pairs the logger in Android's Bluetooth settings and chooses From logger on the Home screen.
2. The app lists the paired devices, then reads the chosen logger's logbook: for each IGC file, its name, date, start time and size.
3. The pilot picks a flight. The app downloads it line by line into a resumable partial file in app-private storage, rebuilds the file byte for byte, and checks its size against the logbook.
4. The file goes into the normal IGC import flow, and the optional WeGlide upload uses the same bytes.
5. The finished file joins the received IGC files (see WeGlide upload), which keep it for a week; the partial download is deleted.

### Protocol as observed on the S100

The source is the LXNAV Data Port Specification 1.04 (2022-07-19, marked Draft and Internal, so only unofficially available). It covers Nano and Nano3 only, and the S100 follows it with the differences noted here. Where the two disagree, this section is what was observed on the device.

**Transport**

- Bluetooth Classic serial port profile. The S100's SDP record lists a Serial Port service (0x1101) on RFCOMM channel 6, plus a separate PnP record.
- Opening the socket is enough to connect. The logger does not hold a connection open while idle, so the OS showing it as disconnected before the app connects is normal.
- All testing was done with no other device connected. Assume the logger serves one client at a time.
- `PLXVC,BT_INFO,R` returned 1, the BM77 module. The specification says to keep blocks small on this module because it has no flow control and can freeze on large transfers.

**Framing**

- A sentence is `$<body>*<CS>` followed by CR LF. The checksum `<CS>` is two hexadecimal digits, the XOR of every character between `$` and `*`. There are no spaces anywhere, and a sentence has at most 256 characters.
- The logger streams NMEA at about 1 Hz (GPRMC, GPGGA, LXWP0 and others), and replies arrive interleaved with it. Ignore anything that is not a valid `PLXV*` sentence, and validate every checksum.
- Replies use query type `A`. The specification's examples sometimes show `R`.

**Commands used**

| Purpose | Send | Reply |
| --- | --- | --- |
| Reset a transfer | `$PLXVC,FILE_CANCEL,R*5C` | None. Send at the start and end of every session. |
| Device info | `$PLXVC,INFO,R*0D` and `$PLXVC,BT_INFO,R*44` | `PLXVC,INFO,A,NINC,9.27,...` and `PLXVC,BT_INFO,A,1`. |
| File count | `$PLXVC,LOGBOOKSIZE,R*4B` | `PLXVC,LOGBOOKSIZE,A,<n>` |
| Logbook | `$PLXVC,LOGBOOK,R,1,<n+1>*<CS>` (the end index is exclusive) | One `PLXVC,LOGBOOK,A,<i>,<n>,<name>,<DD.MM.YYYY>,<HH:MM:SS>,<end>,<bytes>` per file. |
| File lines | `$PLXVC,FLIGHT,R,<name>,<start>,<end>*<CS>` | One `PLXVC,FLIGHT,A,,<line>,<total>,<content>` per line from `start` to `end-1`. |

The checksums shown were computed and confirmed on the device.

**Differences from the specification**

- A `FLIGHT` reply has an extra empty field after `A`. Parse by position, and because the content can itself contain commas (for example `HFFTYFRTYPE:LXNAV,S100`), rejoin everything after the total-lines field.
- Line numbers are 1-based, `total` is the file's line count, and a range that runs past the end returns only the lines that exist.
- Lines arrive without line endings. The original file has CRLF after every line, including the last, and adding it back reproduced the logbook size exactly.
- In the logbook, the start time is UTC and the end time is always `00:00:00`. The file name is not parsed for the date.
- The 1 Hz stream continues during a transfer, so a reply may be preceded by a few stream sentences. A 7-line block arrives in one burst, 60 to 170 ms after the request.

### Download method: FLIGHT line ranges

1. Send `FILE_CANCEL` and discard any pending input.
2. Read the logbook and show it to the pilot.
3. For the chosen file, request lines 1 to 8 in 7-line blocks. The first reply gives the total line count, and the blocks continue to the end.
4. A block is complete when every line number in its range has arrived with a valid checksum. Replies outside the requested range are stale and ignored. Wait up to 4 seconds. On failure, send `FILE_CANCEL`, back off, and ask again. After two failures switch to single-line requests, after three reconnect (waiting about 3 seconds), and give up after eight.
5. Save each completed block as it arrives, so an interrupted download resumes where it stopped.
6. Join the lines, each followed by CRLF, and compare the size with the logbook. A mismatch fails the download.
7. Send `FILE_CANCEL` when the session ends, however it ends.

Requests can be repeated safely, and the device returns identical lines each time. This is why `FLIGHT` is used and the `FILE_INFO` method is not.

**Timing and reliability**

- Throughput is about 0.2 seconds per 7-line block, roughly 2.6 KB/s. A 52 KB file took 22 seconds and an 812 KB file took 311 seconds. A 1.3 MB file would take about 9 minutes, and all ten files on the test unit (5.5 MB) about 40 minutes. These two figures are extrapolated. A large file therefore needs visible progress and a download that survives the screen turning off.
- In the first runs, two stalls happened in roughly 3,000 blocks. At line 7,302 of one file, six retries all failed and the pilot reported that the Bluetooth connection had dropped. This was before reconnecting was added. At line 2,297 of the same file, a block came back one line short and the link then went silent (no bytes at all, not even the NMEA stream). The reconnect step then recovered, which is inferred from the run continuing to line 5,658 afterwards. Later runs had no failures. The cause is unknown.
- The reconnect step and the resume file exist for this: a dropped link costs a few seconds, and no completed block is lost.

**Not used: FILE_INFO block download**

It is strictly sequential, so there is no skip and no jump. A repeated `FILE_OK` and `FILE_DATA_LOST` at block size 1 were both ignored, and cancelling and starting again restarts at line 0. The largest file would need about 13,000 messages. Each message carries a running CRC32 per line whose algorithm could not be identified, so the data cannot be validated. `FILE_CNT` and `FILE_LIST` gave no reply, and the logbook is enough.

### Verified, and not yet verified

**Verified on the S100:** the logbook listing (10 files, matching the earlier phone capture); `FLIGHT` block downloads from the start, middle and end of a file, identical across repeat runs; the downloaded size matched the logbook on every file completed so far, including the 52 KB and 812 KB files; three downloaded files opened cleanly in a web IGC viewer; an interrupted download resuming from the saved line. Automatic reconnect appears to work (see Timing and reliability) but was not seen in isolation.

**Not yet verified:**

- Byte-for-byte equality with a copy from another route, such as XCSoar, Nano Config or the logger's own storage. Download the same flight with a trusted tool and compare hashes.
- The whole-file `FILE_CRC32`, and whether it is a standard CRC-32.
- An uninterrupted download of the 1.3 MB file.
- Any Nano, Nano3 or Nano4. XCSoar 7.44 reports support for protocol 1.05, and the specification in hand is 1.04.
- Whether Nano4 IGC files are signed, so that any change would invalidate them. This is reported but not verified.
- Android: whether a connection by the standard serial UUID works with this module. That connection performs an SDP lookup, and one manual `sdptool browse` dropped the link during testing, so connecting directly to channel 6 may be needed. Treat this as the first thing to test on a device. Earlier testing with the Serial Bluetooth Terminal app on Android, which connects by the standard serial UUID, worked, so the app uses only that documented connection and no direct-channel fallback (2026-10-22).

### Design constraints

- **Paired devices only:** the pilot pairs the logger in Android's Bluetooth settings, and the app lists the already-paired devices without scanning. Android's documentation says an app that communicates with paired devices needs the `BLUETOOTH_CONNECT` permission, a runtime permission on Android 12 and later, and that scanning for devices additionally needs `BLUETOOTH_SCAN`. The legacy Bluetooth permissions apply only up to Android 11. The permission is requested the first time the pilot uses From logger.
- **Layering:** the protocol code (sentence framing, checksums, line reassembly, retries, resume) sits in the `core` package behind a transport interface. The Android Bluetooth transport sits in `data`. A fake transport must be able to reproduce the behaviours above: interleaved NMEA sentences, the extra empty field, commas inside lines, a lost line, a lost reply, a silent stall, and a dropped connection.
- **Files:** an IGC file is stored byte for byte and is never re-encoded, trimmed or converted. It is checked against the logbook size, then kept with the other received IGC files (see WeGlide upload) until it expires.
- **Read-only:** the app must not delete files on the logger, upload files to it, or change its settings or declarations, although the specification describes all three.
- **No long waits for the user:** the download runs with a progress display and can be cancelled, which sends `FILE_CANCEL`.

### UI considerations
- Downloads of large files can take many minutes over BT. A progress indicator is needed to reassure users that the DL is still progressing.
- An estimate of the selected file DL time would be a nice-to-have.
- Files should be shown recent first. Probably only the most recent 3 files is sufficient for a daily flight log. Maybe a "More" button could be helpful for eg instructors who do many short flights in a day.

### As built (2026-10-22, not yet tried on the S100)

- **Downloads:** the screen stays on while a download runs. It shows a progress bar, the line count, the elapsed time and an estimate of the time left. The estimate comes from the logbook size at 2.6 KB/s until the download has run a few seconds, and from the measured rate after that. Before a download, each file shows its estimated download time.
- **File list:** the three newest files are shown first, and More shows the rest.
- **Resume:** one partial download is kept in app-private storage that Auto Backup leaves out. A retry of the same file resumes from it, even after the app restarts. Cancel or a completed download deletes it, and so does age over two days. A resumed download asks again for the last saved line, to learn the total and check the partial belongs to this file.
- **The finished file:** once the size check passes, it joins the received IGC files, kept for a week so a crash or flat battery after a long download loses nothing, and the partial is deleted.
- **Retries and resume:** `LxnavParameters` holds the retry values, and the code follows `lxnav_dl.py`. A dropped link reconnects immediately instead of waiting for the third failure.
- **Device list:** all paired devices are listed, LXNAV ones first.

### XCSoar as a reference

- XCSoar 7.44 (March 2026) lists an LXNAV integration overhaul with protocol 1.05 support and improved flight download, including for Nano devices. XCSoar's documentation site has an LXNAV Protocol page.
- The XCSoar files seen so far carry GPL-2.0-or-later headers, so copying code would bring GPL obligations. Use it as a protocol reference, not a source to copy. This is not legal advice. The driver's source path is not verified.
- XCSoar can also serve as a test oracle: it can download flights from supported LXNAV loggers, and the saved IGC file gives a reference for the byte-for-byte comparison above.

## WeGlide upload

Upload to WeGlide is a button on the Home screen that opens its own upload screen, separate from the logbook (pilot's decision, 2026-10-22). IGC files come from many places (USB, Wi-Fi, a logger download, other apps), some pilots never upload, and some may upload without keeping the logbook, so the upload is independent of saving a flight.

- **Received files (decided 2026-10-23):** every IGC file the app receives, from the picker, a share or a logger download, for logging or for upload, is stored byte for byte in app-private storage outside Auto Backup. Holding it only in memory was too fragile after a long logger download. A file is found again by its content, so receiving it twice does not duplicate it. Each is kept until it is 7 days old or more than 50 are kept (oldest first), or until the pilot removes it. The logbook is still not an archive of IGC files.
- **Both jobs shown everywhere:** each file records which of its flights are logged and whether it is on WeGlide. The logbook import shows the WeGlide status with an Upload to WeGlide button, and the upload screen shows the logbook status with a Log flight button, so a pilot who wants both cannot finish one and forget the other. Pilots who only log, or only upload, can ignore the other line. An uploaded file is not offered for upload again.
- **The file:** one handed over from the logbook import, one of the received files listed on the screen with both statuses, or a new one from the file picker or From logger. The screen shows the file's date, launch time, duration and glider ID for checking.
- **The aircraft:** the glider of a flight saved from the file comes first, then the glider matching the file's glider ID header, preferring one with a WeGlide type. If the chosen glider has no WeGlide ID, picking one on the upload screen also stores it on the glider, with the usual type-change confirmation. With no glider at all, the pilot picks from WeGlide's aircraft list for this upload only, so the logbook is not needed.
- **Credentials:** the WeGlide ID (sent as `user_id`) and date of birth (sent as `date_of_birth`, yyyy-mm-dd), asked for on the upload screen when missing and changeable there, including after a rejected upload. Settings shows whether they are saved and can remove them. They are encrypted with an AES key held in the Android Keystore, stored in the app's no-backup folder, and excluded from all exports. Jetpack's security-crypto library is deprecated in favour of using the Keystore directly.
- **Result:** WeGlide's flight number, scoring date, pilot, aircraft, registration and competition ID, or the failure message with Try again.
- **Precedent:** WeGlide lists XCSoar among the apps that upload directly, and XCSoar has supported WeGlide IGC upload since version 7.23. XCSoar's WeGlide settings ask for a WeGlide ID and a date of birth, which probably explains its low-friction upload.
- **Request:** a multipart POST to `/v1/igcfile` on the WeGlide API, with a `user-agent` header and the body fields `user_id`, `date_of_birth`, `aircraft_id` and `file`. This matches what XCSoar sends. WeGlide describes its API as not finalised and may block non-residential IPs, so the app must handle failures gracefully.
- **Aircraft ID:** each glider stores a `weglide_aircraft_id`, which the pilot sets by searching WeGlide's aircraft list (`GET /v1/aircraft`, with `id`, `name`, `kind`, `sc_class` and `dmst_index` per entry). The app caches the list, searches it ignoring case, spaces and hyphens, and shows kind and class to tell variants apart. Upload is blocked until a glider has an ID, because WeGlide's own guess from the free-text type in the IGC header can pick the wrong variant. Choosing an entry also replaces the glider's type with WeGlide's name, and with no pick the original type stays. The pick sheet shows a line such as "Type will change from ASK21 to ASK 21" before saving, and the original text is lost once saved, though the type stays editable by hand. Clearing the ID later does not restore the old type, and a later rename of the entry on WeGlide does not change the glider's name until the pilot picks again.

## CSV export and import

CSV is for spreadsheets and other systems, not for backup. The app exports flights, gliders and airfields as three files, and import is deliberately strict: the user supplies compliant data or the import fails.

**Flights import**

- **Required columns:** `date` (YYYY-MM-DD), `glider` (registration), `duration` (H:MM or whole minutes), `airfield` and `role` (PIC, STUDENT, INSTRUCTOR, COACH or MUTUAL).
- **Launch type:** expected as `launch_type`. If the column is missing or some cells are blank, the import screen asks once for a default for those rows. An unknown non-blank value fails with the list of valid names.
- **Log number:** if a `log_no` column is present, every cell must be a valid positive integer, used once in the file. A number a live flight already holds is offered as Skip existing or Overwrite (see Overwriting existing flights). If it is absent, the screen offers once to number the rows from the current maximum plus one in file order.
- **Pilot in command:** `pic`, required on STUDENT and MUTUAL rows. On PIC, INSTRUCTOR and COACH rows a blank cell can take the profile name.
- **Optional columns:** `club`, `notes`, and the launch time, landing time and landing place columns described in Appendix C.
- **Validation:** the whole file is checked first, every error is reported with its row number, and nothing is imported if any row fails. A summary is shown, then everything is committed in one transaction.

**Overwriting existing flights (added 2026-10-25, built, no automated tests)**

From TestNotes, 2026-10-25: a flights file can update flights already in the logbook, so a batch of corrections can come in from a spreadsheet.

- **Matching:** only by `log_no`, and only against live flights. A row whose number is held by a live flight is a match; a deleted flight's number counts as free. Without a `log_no` column nothing matches, and every row is new as today. A number repeated within the file is still an error.
- **Choice:** when the otherwise valid file has matches, the confirmation screen shows how many rows match existing flights, and Import stays off until the pilot picks **Skip existing** (import only the new rows) or **Overwrite** (import the new rows and update the matches); Cancel leaves everything as it was. All other errors still block the import first. Stubs, the default launch type and the default pilot in command are asked only for the rows the choice imports, and an overwrite asks for them only when the file has that column.
- **Check before overwriting:** the screen lists each match whose date or glider differs from the existing flight, because that usually means a wrong number in the file. It also says how many witnessed flights would show Changed since witnessed. A row whose time would no longer come after the flight's kept launch time (the file has only one of the two time columns) blocks Overwrite and is listed; Skip existing is still possible. A STUDENT or MUTUAL row with no `pic` column keeps the flight's pilot in command instead of failing.
- **What an overwrite changes:** every column present in the file replaces the flight's value, and a blank cell in an optional column clears it. A column the file leaves out keeps its existing values. Without a `club` column the flight keeps its club while that club is one of the new airfield's, otherwise the one-club rule applies. A landing airfield or place from the file clears the other one. The flight keeps its ID, source, import batch and witness mark, so a change to a signed field shows as Changed since witnessed, exactly as an edit in the form would. The pilot profile name fills a blank `pic` only as it does for new rows.
- **Safety copy:** before overwriting, the app writes a JSON backup to the backup folder, and Overwrite is offered only once a backup folder is set. Undo last import removes the import's new rows and stubs but cannot reverse an overwrite; the confirmation says so and names the safety copy as the way back.
**Unmatched gliders and airfields**

- The confirmation screen lists them and creates minimal stub records: registration only for a glider, name only for an airfield. Stubs are flagged incomplete until the pilot fills them in.
- Each unmatched name has a "use existing…" dropdown to avoid near-duplicates such as "Mt Beauty" and "Mount Beauty".
- Every import, of flights, gliders or airfields, is tagged with a batch ID, and Settings offers "Undo last import", which also removes that batch's unused stubs. Undo can be repeated, each time removing the most recent remaining import. It removes the batch's flights even if they were edited since, keeps any glider or airfield the batch created that another live flight now uses, and leaves clubs and club links the import created. Removed records are kept as tombstones so a later Merge respects the removal, and the confirmation shows the counts first.

**Export:** Settings, Spreadsheets (CSV), Export to a folder writes all live flights (not the current filter), gliders and airfields as three timestamped files, for example `soarlog-flights-20261021-1405.csv`.

**Gliders and airfields files:** each has its own export and import (gliders: registration and type; airfields: name, coordinates and club names), so a fleet or field list can be loaded without parsing it from the log.

**Worked example:** the pilot's existing logbook CSV needs spreadsheet preparation first. Dates change from dd/mm/yy to YYYY-MM-DD, the Place column (for example `YMBT MBGC`) splits into airfield and club, the In Command values are milliseconds and divide by 60,000 to give minutes, and launch type names such as Winch and Aero must match the app's list.

## Backup and restore

A lost phone must not lose the logbook, so one JSON file is the only backup and restore format. There are no SQLite file backups and no zip of CSVs.

- **Contents:** every table, including archived records, tombstones, opening balances, witness sign-offs with their photos, and non-secret settings. WeGlide credentials are never included. Include witness photos (on by default, kept on this phone only) leaves the photos out while keeping names, numbers, hashes and seals; the manifest's `contains_photos` says whether a file holds them.
- **Manifest:** a format name, a format version independent of the Room schema version (2 since witness sign-off), the export time, the app version and `contains_photos`.
- **Restore:** replaces everything in an empty logbook, which means no flights, gliders, airfields or clubs, deleted or not. The seeded launch types and zero opening balances are replaced by the file's. Restore also loads every backed-up setting, including the pilot profile. **Merge** upserts by UUID with the newest `updated_at` winning and never changes settings or the profile. A `log_no` collision, or any other break of a uniqueness rule, is listed and nothing is committed. The pilot fixes the numbers in the app and merges again; there is no in-place resolution screen.
- **Safety:** import runs in one transaction in dependency order, validates references, enum values and durations first, and shows a summary before committing. A file from a newer format version is refused, and an older one is upgraded.
- **Off-phone copies:** the pilot picks a folder once, which can be cloud-synced, and the app writes timestamped JSON exports there on a schedule or after a set number of changes. Both triggers are Settings, defaulting to 20 changes and 7 days; 0 turns a trigger off, and both at 0 turn automatic backups off. A Back up now button in Settings writes one at once. Names are unique because the file-create picker can't overwrite, and the last 10 files are kept.
- **First run:** the first screen offers Start fresh or Restore from backup. Settings also offers restore.
- **Android Auto Backup:** left at its default. If a restored database already exists on first run, the app opens it normally. Room migrations cover every released schema version, and missing credentials are treated as a normal re-entry case.

**Test:** export, import into an empty database, export again, and compare the two files, which should be identical with stable ordering apart from `exported_at`.

## Templated PDF creation

### Approach: 
The PDF is drawn on the device with Android’s PdfDocument API, driven by a JSON template. There is no PDF library and no preview step.

### Filters: 
The export uses whatever filters are currently applied, so there is no override switch. The dialog shows the flight count and a one-line filter summary, and the same summary is printed in the page footer.

### Templates
A template is a JSON file that sets page size and orientation, margins, the columns, the totals rows and the header and footer.
Each template decides how role is shown, by listing which role columns it has. The GFA-style template has only PIC and Dual. Each flight’s duration goes in the column its role maps to. The template defines that mapping, including where Student, Instructor, Coach and Mutual time lands. I’ll settle that once I see a page of the real logbook.
The built-in templates are Simple list, Compact and GFA-style. A user can import further templates from a file. There is no in-app editor, now or later.

Totals: Page total, brought forward and carried forward, running within the exported set only. The footer says so when the export is filtered. They use the same arithmetic as the on-screen totals.

Settings: The H:MM or decimal hours setting applies. The place and times columns only appear when those settings are on and the template includes them.

Fonts: One bundled font in regular and bold, chosen for wide glyph coverage so it still works once the app is multi-language.

Output: The user picks a destination with the system file picker, or uses the share sheet. The work runs in the background with progress and a cancel button, writing pages as it goes so 10,000 flights stay within memory.

### Testing: 
Check page and total arithmetic against the on-screen totals, run a 10,000-flight export, and confirm a filtered export contains only the filtered flights.

### Decisions (2026-10-23)

- **Where:** the export starts from Settings, beside Spreadsheets (CSV).
- **Brought forward:** with no filter active, the first page brings forward the opening balances, so an unfiltered export continues the paper logbook and matches the on-screen Totals. A filtered export starts from zero, and the footer says so.
- **GFA-style template:** taken from a GFA GPC logbook spread (`testdata/Extract pages from GFA GPC LogBook.pdf`). Each page is A5 portrait, so a spread is A4 landscape, and each PDF page is one spread.
  - Left half: Serial No. of Flight, Date, Glider Type/Rego, Place, Launch Type, and two time columns, Dual/Inst. and In Com., each as Hrs and Min. A "Year:" and "Totals brought forward" row at the top; "Total for page" and "Grand totals to date" at the foot.
  - Right half: Remarks (the flight's notes; the book's heading is Exercise / Task / Turnpoints / Landing point / Km), a Trainers Signature column (the witness's name and number for a witnessed flight whose seal still matches, while Witness sign-off is on; otherwise empty for a hand signature), and "Other Totals" at the foot, left empty.
  - **Roles:** Student goes in Dual/Inst., and PIC and Mutual in In Com. Instructor and Coach follow the Setting that counts them toward PIC: In Com. when it is on, Dual/Inst. when it is off.
  - Rows per page are about 20, to be matched to the book.
- **Font:** Roboto regular and bold, v3.016 unhinted static files from googlefonts/roboto-3-classic, in `res/font/`, with the SIL Open Font License in `assets/licenses/Roboto-OFL.txt` (2026-10-23).

### As built (2026-10-23, not yet tried on a phone)

- **Settings → Logbook PDF → Create PDF** opens the export screen. It shows the number of flights, the filter in one line (or that page 1 brings forward the opening balances), the templates to choose from, Import template, Save to file and Share. Progress shows page by page, with Cancel.
- **Memory:** Android's PdfDocument writes the whole document at the end (`writeTo`) and has no way to write page by page. The app reads flights from the database 500 at a time and draws each page as soon as it is full, so only PdfDocument holds the finished pages. They are text and lines, so they should stay small; the 10,000-flight test will show the real figure.
- **Order:** oldest first, by date then log number, like a paper logbook. The flights are those the Flights screen's filter shows.
- **Totals rows:** brought forward at the top of each page, then the page total and carried forward at the foot, each with its number of flights. Every flight counts once in each time column whose roles take it, as on the Totals screen.
- **Templates:** Simple list (A4 landscape, 25 rows), Compact (A4 portrait, 55 rows) and GFA-style (A4 landscape spread, 20 rows), in `assets/pdf-templates/`. Imported templates are kept in app-private storage, where an import with the same name replaces the earlier one, and they can be removed.

### Later: German glider logbook template (not built)

Reference pictures are in `testdata`: `flugbuecher segelflug A.jpg` (left page) and `B.jpg` (right page) are readable; `flugbuecher segelflug.jpg` shows the two as one spread. `schiffmann flugbuch.webp` is an EASA powered-aircraft logbook (SEP, Night and IFR columns), usable only as a rough reference for a general EASA template. Recorded 2026-10-23, to build later.

**Layout:** landscape A5 pages, 10 rows each, so the open spread is about 420 × 148 mm and does not fit A4.

- **Left page:** Lfd. Nr. · Jahr above Tag · Flugzeug (Muster, Kennzeichen) · Besatzung (Verantwortlicher Luftfahrzeugführer (PIC), Begleiter) · Startart · Ort (Start, Landung). Below the table: "Unterschrift des Piloten für die Richtigkeit der Eintragungen".
- **Right page:** Uhrzeit UTC (Start, Landung) · Flugzeit (Gesamtzeit, PIC-Zeit, Dual-Zeit, FI/FE-Zeit, each in h and min) · Strecke in km · Anzahl Landungen · Bemerkungen / Bestätigungen. "Überträge" (brought forward) across the top of the time columns, and "Gesamtzeit dieser Seite" and "Total" at the foot.

**What the engine needs first:**

1. **Arrival place:** a field that prints the landing airfield or place, and the departure airfield when the flight ended where it began. Today `landing_place` prints only with the Place setting on and is blank for same-place flights. GAus (Appendix G) also asks for departure and arrival.
2. **Landings count:** a column with 1 per flight, totalled.
3. **UTC times:** times are stored as local clock times, so printing UTC means converting with the phone's time zone. That is right at home, but wrong for a trip flight in another zone.
4. **Two PDF pages per spread:** a page-break column, so the left page's columns go on one PDF page and the right page's on the next, each half with its own totals rows. GFA-style could then also print as two A5 pages.
5. **Four time columns:** Gesamtzeit (every role), PIC-Zeit, Dual-Zeit and FI/FE-Zeit (instructor), each split into h and min. The role mapping is to be decided. Under EASA rules FI time is also PIC time, so one flight may land in two columns, which the engine already allows.
6. **Not recorded by the app:** Begleiter (the other crew member) and Strecke in km print as blank columns.
**Template format (version 1):** a JSON object with `format` set to `soarlog-pdf-template` (templates saved with the earlier `soarlogs-pdf-template` still load), `version` (1), `name`, and:

- `page`: `size` (A4, A5, LETTER, or CUSTOM with `width_mm` and `height_mm`) and `orientation` (portrait or landscape).
- `margins_mm`: `top`, `bottom`, `left`, `right`. `font_size` in points (4 to 20) and `rows_per_page` (1 to 200).
- `header`: `title` (left out: the app's "Pilot logbook" in the pilot's language; an empty string prints none) and `show_pilot` (the profile name and identifiers).
- `labels`: optional `brought_forward`, `page` and `carried_forward` text for the totals rows; left out, the app's own text.
- `columns`, in order, each with `field` and optional `width` (relative), `title` (heading lines; left out, the app's heading), `align` (start, center or end) and `total`.
  - `field` is one of: `log_no`, `date`, `glider` (registration and type), `registration`, `glider_type`, `airfield`, `place` (airfield and club), `club`, `launch_type`, `role`, `pic`, `duration` (every flight), `time` (only the roles in `roles`), `launch_time` and `landing_time` (printed only with the Times setting), `landing_place` (only with the Place setting), `notes`, `witness` (the witness's name and number for a witnessed flight whose seal still matches, while Witness sign-off is on; otherwise empty), `blank` (an empty ruled column, such as a signature) and `gap` (unruled space, such as the fold of a spread).
  - A `time` column lists its `roles` (PIC, STUDENT, INSTRUCTOR, COACH, MUTUAL) and sets `instruction` to `never`, `always`, `when_pic` or `when_not_pic` to say whether Instructor and Coach time goes there, following the Setting that counts them toward PIC.
  - `split_hm` prints Hrs and Min sub-columns, as in a printed book, when hours are shown as H:MM. Time columns are totalled unless `total` is false.
- Unknown fields are ignored. A file with another `format`, or a newer `version`, is refused, and so is one with a missing name, no columns, a `time` column without roles, or an unreadable page size or margins.

## Witness sign-off

A witness, such as an instructor, confirms flights in the app with a name, an optional licence or membership number, and a photo of their face. Each witnessed flight carries a seal, so a later edit to a signed field shows. The full design, its limits and the exact seal text are in `docs/Witness_Sign-Off.md`; this section is the summary (adopted and built 2026-10-23).

- **Setting:** Witness sign-off, off by default and backed up. Turning it off hides every witness screen, mark, filter, CSV column and PDF entry, and deletes nothing.
- **Signing:** from a day header in the Flights list (the day's unwitnessed flights start ticked) or from a flight's witness card. The witness types a name and optionally a number, or taps an earlier witness; reads what is stored and why; takes a photo with the phone's own camera app; and ticks the consent line. One sign-off covers the ticked flights, and each gets its own seal.
- **Required:** the name and the photo, because the photo is the evidence of who witnessed. The number is optional. A witness matching the pilot profile's name or any of its numbers is refused. On Student flights the name is pre-filled from the PIC when all ticked flights share one, with a warning if the witness is someone else.
- **Photo:** taken through the system camera, so no CAMERA permission. Turned upright, scaled to 200 pixels on its longest side and re-encoded as JPEG (quality 70, falling back to 55 and 40 to stay within 25 KB), which drops all EXIF data, location included. The camera's original is deleted at once. Stored in the sign-off row.
- **Seal:** SHA-256 over the flight's log number, date, duration, glider, airfield, launch type, role, PIC, times and landing, plus the witness's name, number, signing time and photo hash. Notes and club are not signed. The status (Witnessed, Changed since witnessed, Cannot verify for an unknown seal version) is worked out whenever it is shown and never stored.
- **Wording:** the screen shows "Your name, the number if given, the time of signing and a small photo of your face are kept with these flights in this logbook on this phone and in its backups. They show who witnessed the flights, and let the app notice a later change." The consent line is "I witnessed these flights and agree that my name, number and photo are stored with them in this logbook."
- **Flight form:** a witness card shows the name and number, the signing date (with "N days after the flight" when later), the thumbnail and the status, with Witness again, Remove photo and Remove witness mark. Saving a change to a signed field of a flight that still matches its seal first asks "This flight was witnessed by X. Changing it will mark it as changed since witnessed."
- **Removal:** Remove photo deletes the photo and keeps its hash, so the seals stay valid. Remove witness mark detaches the witness from one flight. A sign-off that no live flight uses is deleted with its photo, also after a flight is deleted, witnessed again, or removed by Undo last import, Restore or Merge.
- **Exports:** the JSON backup carries the sign-offs and photos (Appendix B). The flights CSV and the PDF's `witness` column show the witness only for flights whose seal still matches (Appendix C, Templated PDF creation). Nothing is ever uploaded, including to WeGlide.
- **Privacy:** the feature is opt-in, the photo is small and local, the pilot can delete it at any time (for example at the witness's request), and a backup can leave the photos out. Android Auto Backup copies the database, photos included, as for the rest of the logbook; a pilot who wants nothing on Google turns Auto Backup off. The seal detects edits but has no secret, so it is not proof of identity.

## Appendix A: Database schema

The appendices hold proposed defaults written while preparing this spec for implementation, and an implementer should ask before departing from them. Primary keys are UUID strings (except opening\_balance, keyed by role, and airfield\_club, with a composite key), dates are ISO text (YYYY-MM-DD), and instants are epoch milliseconds in UTC. Tables with a tombstone have `deleted_at`, null for live rows.

| Table | Columns | Constraints and indexes |
| --- | --- | --- |
| flight | id, log\_no (integer, required), date (text, required), duration\_minutes (integer, 1 to 1440), glider\_id, airfield\_id, club\_id (nullable), launch\_type\_id, role (text, required), pic\_name (text, required, non-blank), launch\_time (text HH:MM, nullable), landing\_time (text HH:MM, nullable), landing\_airfield\_id (nullable), landing\_place (text, nullable), notes (nullable), source (MANUAL, IGC or CSV), import\_batch\_id (nullable), updated\_at, deleted\_at, witness\_signoff\_id (nullable), witness\_seal (text, nullable) | Unique index on `log_no` for live rows. Indexes on date, glider\_id, airfield\_id, club\_id, role, import\_batch\_id and witness\_signoff\_id. Foreign keys restrict deletion. The two witness columns are set together or both null (added in schema version 3). |
| glider | id, registration, registration\_key, type (text, empty allowed for stubs), weglide\_aircraft\_id (integer, nullable), logbook\_total\_minutes (integer, nullable), logbook\_total\_flights (integer, nullable), logbook\_total\_date (text YYYY-MM-DD, nullable), incomplete, archived, import\_batch\_id (nullable), updated\_at, deleted\_at | Unique index on `registration_key` for live rows. The key is the registration lower-cased with spaces and hyphens removed and nothing else changed, so `?` stays valid. The logbook total minutes and date are set together, and the flights count is optional and may be removed later. |
| airfield | id, name, name\_key, latitude, longitude, elevation\_m, match\_radius\_m (null means the default), default\_club\_id (nullable), default\_launch\_type\_id (nullable), incomplete, archived, import\_batch\_id (nullable), updated\_at, deleted\_at | Unique index on `name_key` (trimmed, lower-cased) for live rows. Incomplete means no coordinates. `elevation_m` is an addition to the data model, used to pre-fill a new airfield from an IGC file. The two defaults are foreign keys that restrict deletion, added in schema version 2. `default_club_id` must be one of the airfield's linked clubs and is cleared when that link is removed. |
| club | id, name, name\_key, updated\_at, deleted\_at | Unique index on `name_key` for live rows. |
| airfield\_club | airfield\_id, club\_id | Composite primary key. Rows are deleted outright when links change, and the airfield's `updated_at` is bumped, so a Merge treats the link set as part of the airfield. |
| launch\_type | id, name, name\_key, sort\_order, archived, updated\_at, deleted\_at | Seeded with Aero, Winch, Self and Auto using fixed UUIDs derived from the names, so a restore into a fresh install cannot duplicate them. |
| opening\_balance | role (primary key), as\_of\_date, minutes, flights, updated\_at | Exactly five rows, one per role, created with zeros on first run. |
| import\_batch | id, created\_at, file\_name, flight\_count | Used by Undo last import, which removes the flights, stubs and batch row. |
| witness\_signoff | id, witness\_name, witness\_identifier (nullable), signed\_at, photo (JPEG BLOB, nullable), photo\_sha256 (nullable), photo\_removed, seal\_version, consent\_confirmed, updated\_at, deleted\_at | Added in schema version 3. The name is non-blank and consent true; both rules and the 100 KB photo limit are kept in the repository, not in SQL. Name and number are never edited after signing. A photo is required when signing, so `photo` is null only after Remove photo or a backup without photos, and the hash stays. Deleting a sign-off leaves a tombstone without its photo. |

**Notes**

- Roles are stored as the text names PIC, STUDENT, INSTRUCTOR, COACH and MUTUAL.
- Room's index annotation cannot express a WHERE clause, so the partial unique indexes are created with raw SQL in the database callback, both on creation and on every open. Room's schema validation after a migration rejects indexes it does not know (confirmed on a device on 2026-10-20), so every migration drops the partial indexes first and the open callback recreates them. Test that they enforce uniqueness.
- Settings live in DataStore: `hours_display` (HM or DECIMAL), `instruction_counts_as_pic` (boolean), record\_landing\_place (boolean), record\_times (boolean), pilot\_name (text), pilot\_identifiers (a list of label and value pairs), `backup_after_changes` and `backup_after_days` (integers, 0 for off) and `witness_signoff` (boolean) are backed up. `default_glider_id` and `default_airfield_id` (IDs, nullable, added 2026-10-25) are backed up too. `backup_folder_uri`, `last_backup_at`, `first_run_done` and `backup_witness_photos` (boolean, default true) are specific to the device and are not.
- The WeGlide ID and date of birth are encrypted with an AES-GCM key held in the Android Keystore, in a file in the no-backup folder. The AndroidX security-crypto library was not used because it is deprecated in favour of the Keystore directly (checked 2026-10-22).
- Totals use `SUM(duration_minutes)` and `COUNT(*)` per role over live flights with the active filters. With no filter active, the opening balance is added. Combined counts each flight once, and when the Settings option that counts instruction as PIC is on, the PIC row also includes Instructor and Coach flights.

## Appendix B: JSON backup format

The backup is one UTF-8 JSON file whose top level holds the manifest fields and one array per table. Field names match the schema columns, with timestamps written as ISO 8601 UTC strings.

```
{
  "format": "glider-logbook-backup",
  "format_version": 2,
  "exported_at": "2026-10-18T09:47:00Z",
  "app_version": "1.0.0",
  "contains_photos": true,
  "launch_types": [ ... ],
  "clubs": [ ... ],
  "airfields": [ { "id": "...", "name": "YMBT", "club_ids": ["...", "..."], ... } ],
  "gliders": [ ... ],
  "flights": [
    {
      "id": "...", "log_no": 340, "date": "2026-10-17",
      "duration_minutes": 271, "glider_id": "...", "airfield_id": "...",
      "club_id": "...", "launch_type_id": "...", "role": "PIC", "pic_name": "...",
      "notes": null, "source": "CSV", "import_batch_id": null,
      "updated_at": "2026-10-18T09:47:00Z", "deleted_at": null,
      "witness_signoff_id": null, "witness_seal": null
    }
  ],
  "witness_signoffs": [
    {
      "id": "...", "witness_name": "...", "witness_identifier": null,
      "signed_at": "2026-10-18T09:47:00Z", "photo": "/9j/...", "photo_sha256": "...",
      "photo_removed": false, "seal_version": 1, "consent_confirmed": true,
      "updated_at": "2026-10-18T09:47:00Z", "deleted_at": null
    }
  ],
  "opening_balances": [ ... ],
  "import_batches": [ ... ],
  "settings": { "hours_display": "HM", "instruction_counts_as_pic": false }
}
```

- **Stable output:** arrays are sorted by `id` (opening balances by role), keys are written in a fixed order, and the file is indented with two spaces, so an export, import and export round trip produces identical files.
- **Contents:** archived records and tombstones are included. Each airfield carries its club IDs in place of the link table. Credentials and device-specific settings are excluded. Flight objects also carry launch\_time, landing\_time, landing\_airfield\_id and landing\_place (null when unused), and the settings object carries record\_landing\_place, record\_times, backup\_after\_changes, backup\_after\_days, pilot\_name and pilot\_identifiers. Airfield objects also carry default\_club\_id and default\_launch\_type\_id.
- **Version rules:** a file whose `format` differs from the name above is refused, a `format_version` newer than the app supports is refused with a clear message, and older versions are upgraded step by step. Unknown fields are ignored. Version 2 (2026-10-23) adds the witness data; the 1 to 2 upgrade only sets the version, because every new field defaults to empty. An app that knows only version 1 refuses a version 2 file instead of dropping witness data.
- **Witness data:** `photo` is the stored JPEG in standard base64 (written unchanged, so the round trip stays identical), or null when removed or left out by Include witness photos. `contains_photos` is true when any photo is present. The settings object also carries `witness_signoff`, and `default_glider_id` and `default_airfield_id` (added 2026-10-25, null when unset; an older version 2 file without it restores with no default).
- **Witness import checks:** a sign-off with no name, no consent, an unreadable time or a duplicate id refuses the file like any other problem. A photo that is not valid base64, not a JPEG, over 100 KB or not matching its `photo_sha256` is dropped and reported, and the sign-off kept. A flight whose sign-off is missing (or deleted while the flight is live), or with only one of its two witness fields, keeps its data, loses both fields and is reported. Every seal is rechecked, and the Restore or Merge dialog shows how many witnessed flights are verified, changed since witnessed and cannot be verified. A mismatch never blocks the import.
- **Restore and Merge:** Restore requires an empty database. Merge matches by `id`, the newer `updated_at` wins (opening balances match by role), tombstones are respected, and a `log_no` collision between different flights is listed and blocks the commit until resolved. Sign-offs match by `id` too. A sign-off whose photo was left out, not removed, takes the photo from the other side when the hashes match, so merging a backup without photos never loses them and merging a full backup fills them in. A newer flight brings its own seal, which then shows whether it still matches.

## Appendix C: CSV formats

All files are UTF-8 with a comma delimiter, a single header row, and RFC 4180 quoting (fields containing commas, quotes or line breaks are quoted, with inner quotes doubled). Export writes a UTF-8 byte order mark so spreadsheets read accented names correctly, and import accepts files with or without one. Header names are matched ignoring case and surrounding spaces, blank rows are skipped, and unlisted columns are ignored with a warning on the summary screen.

**flights.csv** (export column order: log\_no, date, glider, airfield, club, launch\_type, role, pic, duration, launch\_time, landing\_time, landing\_airfield, landing\_place, notes, then witnessed\_by and witnessed\_on while Witness sign-off is on)

| Column | On import | Format |
| --- | --- | --- |
| log\_no | Optional | Positive integer. See the log number rules above. A number held by a live flight is offered as Skip existing or Overwrite (see Overwriting existing flights). |
| date | Required | YYYY-MM-DD |
| glider | Required | Registration, matched ignoring case, spaces and hyphens. |
| duration | Required | H:MM (minutes may exceed 59, so 1:75 is valid) if it contains a colon, otherwise whole minutes. At least one minute. Export writes H:MM. A bare number means whole minutes here, unlike the flight form, where 324 means 3:24. |
| airfield | Required | Airfield name, matched ignoring case after trimming. |
| role | Required | PIC, STUDENT, INSTRUCTOR, COACH or MUTUAL |
| launch\_type | Prompted if missing or blank | A launch type name, matched ignoring case. |
| club | Optional | Club name. A club that doesn't exist is created and linked to the airfield. A blank cell leaves the club unset, even for an airfield with several clubs. |
| notes | Optional | Free text. |
| pic | Required on STUDENT and MUTUAL rows | The pilot in command's name. A blank cell on a PIC, INSTRUCTOR or COACH row can take the profile name. |
| launch\_time, landing\_time | Optional | HH:MM, local 24-hour. Landing after launch. |
| landing\_airfield | Optional | An airfield name, matched like `airfield`. Not with landing\_place. |
| landing\_place | Optional | Free text. Not with landing\_airfield. |
| witnessed\_by, witnessed\_on | Ignored, with no warning | Export only, while Witness sign-off is on: the witness's name with the number in brackets, and the signing date (YYYY-MM-DD, the phone's time zone), filled only for a flight whose seal still matches. Import never creates a witness mark, so a spreadsheet cannot witness a flight. |

**gliders.csv:** `registration` (required), `type` (required), `weglide_aircraft_id` (optional positive integer), `weglide_aircraft_name` (written on export for readability, ignored on import), and `archived` (optional).

**airfields.csv:** `name` (required), `latitude` and `longitude` (optional decimal degrees), `elevation_m` (optional integer), `match_radius_m` (optional integer), `clubs` (optional, club names separated by semicolons) and `archived` (optional).

**archived** (both files, added 2026-10-23): `yes`, `no` or blank, ignoring case, blank meaning not archived. Export writes `yes` or blank. A pilot can import a whole region's airfields archived and un-archive only those in use, which keeps the dropdowns short. As with every column, an existing glider or airfield is skipped, so importing does not change its archived flag.

**Behaviour**

- A glider or airfield row whose key already exists, in the logbook or earlier in the file, is skipped and reported, never updated. Airfields without coordinates are flagged incomplete.
- Role and launch type values match ignoring case. A blank club cell is filled only when the airfield ends up with exactly one club.
- A flights import first resolves gliders and airfields, offering stubs and the "use existing…" choice for unmatched names, then asks about the launch type and log number defaults if needed, then commits everything in one transaction tagged with a batch ID.
- Errors are listed as file, row (the header is row 1), column, the value found and what was expected, for example `Row 14, date: found 18/10/26, expected YYYY-MM-DD`. Show up to 200 errors, then a count of the rest.

**Optional columns for the two Settings:** `launch_time` and `landing_time` (HH:MM, local 24-hour), `landing_airfield` (an airfield name, with unknown names handled like other unmatched airfields) and `landing_place` (free text). Setting both landing columns on one row is an error, and a landing time must be after the launch time. The duration column stays required.

**PIC column:** `pic` holds the name of the pilot in command. If the column is missing or some cells are blank, the import screen offers once to use the pilot profile name for the rows whose role is PIC, INSTRUCTOR or COACH. A blank cell on a STUDENT or MUTUAL row fails with its row number.

## Appendix D: IGC parsing parameters

The record layouts below were checked on 2026-10-21 against Appendix A of the FAI IGC technical specification (January 2026, AL10): A2.5.4 gives HFDTE as the UTC date of the first valid fix, the H record list gives `HFDTEDATE:DDMMYY,NN` (NN is the flight number of the day) and `HFGIDGLIDERID:`, and A4.1 gives the B record layout, with A for a 3D fix and V for a 2D fix or no GNSS data. The numeric defaults are proposals, kept as named constants in one place so they can be tuned against real files.

**Parsing**

- **Date:** the HFDTE header carries DDMMYY (a newer form is `HFDTEDATE:DDMMYY,NN`), with the two-digit year read as 20YY. This is the UTC date of the first fix.
- **Fixes:** B records give the UTC time as HHMMSS, latitude as DDMMmmmN or S, longitude as DDDMMmmmE or W (minutes in thousandths), a validity flag, and pressure and GNSS altitudes. Use only fixes marked valid (A) and use GNSS altitude. When a fix time is earlier than the one before, add a day.
- **Speed:** the haversine distance between consecutive valid fixes divided by the time between them. Pairs more than 60 seconds apart are skipped.

**Detecting a flight**

- **Start:** ground speed at or above 50 km/h held for 10 seconds. The segment starts at the first fix of that run.
- **End:** ground speed below 50 km/h held for 30 seconds. The segment ends at the first fix of that run.
- **Guards:** the segment must last at least 30 seconds and climb at least 50 m above its starting altitude at some point. The threshold is low because the pilot's logbook contains one-minute cable-break flights.
- **Known weakness:** in a strong headwind a glider's ground speed can drop below 50 km/h in flight. If real files show false endings, add a condition that the low speed coincides with being close to the ground.
- **Measured against the sample files (2026-10-21):** compared with another IGC reader, the speed rule puts launch 3 to 6 seconds earlier and landing 17 to 36 seconds later, so durations run 20 to 42 seconds longer. All six files are within the one-minute tolerance, but two round to one minute more. The traces show the cause. The other reader's landing comes about 12 seconds before touchdown, with the glider still about 40 m up. The speed rule's landing comes at the end of the ground roll, about 20 seconds after touchdown. Systems define launch and landing differently, so the speed rule stays for now.

**Other ways to find launch and landing (not built)**

These are options to weigh if the speed rule proves unsuitable. Any of them can be combined with the speed rule, which would still find the segments.

- **Touchdown by height:** after the speed rule finds the end of the ground roll, step back to the first fix where GNSS altitude has settled within a few metres of the final ground altitude. This gives touchdown rather than the end of the roll. Lift-off can be found the same way, as the first fix that climbs a few metres above the ground altitude at the start.
- **Speed with height above the ground:** require the low speed to coincide with being near the launch or landing ground altitude. This also deals with false endings in a strong headwind.
- **Airspeed:** use the true airspeed (TAS) extension that some recorders, including LXNAV, add to B records, declared in the I record. Airspeed is unaffected by wind, but not every recorder writes it.
- **Engine noise and propulsion:** the ENL and MOP extensions mark engine running. They do not mark launch or landing, but would be needed to tell a self-launch's climb from soaring.
- **Recorder flight markers:** some recorders log their own takeoff and landing events, in proprietary L records. These are not part of the IGC standard and vary by maker.
- **Different thresholds:** a higher speed or longer hold moves both ends. It risks missing the short cable-break flights the current thresholds were set for.

**Results**

- **Duration:** landing time minus launch time, rounded to the nearest whole minute, with a minimum of one minute.
- **Flight date:** the launch instant converted to the device's timezone, taking its calendar date.
- **Preview:** launch and landing times in local time, as HH:MM, for checking, and stored in the flight only when the Times setting is on.
- **Several segments:** listed with local launch time and duration. Each is imported separately and takes the next log number in turn.

**Matching**

- **Airfield:** measure from the launch point, which is the first fix of the segment, to each airfield with coordinates. The default match radius is 1000 m, overridden by `match_radius_m` where set. Choose the nearest airfield within its radius and list others in range with their distances. With no match, offer to create one, pre-filled with the launch coordinates and the fix altitude as the elevation.
- **Glider:** compare the glider ID header (`HFGIDGLIDERID`) with the glider registration key. If nothing matches, compare it again without its nationality prefix, the text before the first hyphen, so `VH-FQM` finds a glider entered as `FQM`. If there is still no match, the pilot chooses. The glider type header is ignored. The prefix rule was added 2026-10-21, because the pilot's gliders are entered without `VH-`.

**Landing place (when the Place setting is on):** compare the landing fix, the last fix of the segment, with the launch airfield. Inside its radius, leave the landing place blank. Inside another airfield's radius, preselect that airfield. Otherwise pre-fill the free-text landing place with the coordinates, for example -36.7842, 147.1700, for the pilot to rename. No create-airfield prompt is shown for landings.

**Tests:** keep a handful of the pilot's own IGC files in the repository with the date and duration written in the logbook for each, and assert that the parser agrees to within a minute.

## Appendix E: WeGlide client

All WeGlide code sits behind one interface with a fake implementation for tests. The base URL is a single constant, `https://api.weglide.org`, with `GET /v1/aircraft` and `POST /v1/igcfile`. The pilot supplied both paths, and the shared host is an assumption to check with a first real call.

- **Headers:** every request sends a browser-style `User-Agent` that names the app, `Mozilla/5.0 (Linux; Android) SoarLog/<version>` (`soarlogs/<version>` until the rename on 2026-10-23; the renamed form is to be confirmed with the next manual upload). WeGlide's firewall (an AWS load balancer) refused `soarlogs/0.1.0 (Android)` and `soarlogs/0.1.0` with an empty 403 on 2026-10-21, while it accepted this form, a plain browser string and `XCSoar/7.44`. WeGlide only makes an allowance for an app once it has 1,000 users (pilot's enquiry, 2026-10-21), so the browser-style form stays. Do not change it back to the plain form.
- **Aircraft list:** GET with no credentials. The response is a JSON array whose entries have `id`, `name`, `kind`, `sc_class` and `dmst_index`. Skip entries without a numeric `id` and a string `name`. The app ships with a copy of the list (`assets/weglide-aircraft.json`, from WeGlide, redistribution confirmed by the pilot) and uses it until the pilot taps Update in Settings. An update saves the raw response in app-private storage, which then replaces the bundled copy, and keeps the list in use if it fails or returns nothing usable. There is no automatic refresh, because new types are rare. Settings shows the number of aircraft and the date of the last update.
- **Search:** lower-case both strings, remove accents, and remove everything except letters and digits. Rank exact matches first, then starts-with, then contains, ties by name. Show the name with its `kind` and `sc_class` exactly as returned.
- **Picking:** store the ID on the glider and replace its type with WeGlide's name, after showing the confirmation line described above.
- **Upload gate:** a glider needs a `weglide_aircraft_id`, and the pilot needs the WeGlide ID and date of birth in Settings. If any is missing, prompt for it instead of failing.
- **Request:** multipart form data with `file` (the IGC bytes, with a name ending in .igc), `user_id` (digits), `date_of_birth` (YYYY-MM-DD) and `aircraft_id` (integer as text).
- **Response:** HTTP 201 means success, as in XCSoar's source. Read the first element of the returned array and show its flight `id`, `scoring_date`, `registration`, `competition_id`, `user.name` and `aircraft.name`. Any other status is a failure: show the status code and the first 300 characters of the message, and do not retry automatically. A network error shows its message and offers a retry.
- **Timeouts:** connect 15 seconds, read and write 60 seconds.
- **Safety:** HTTPS only and never log the credentials. The IGC file is one of the received files, kept only until it expires (see WeGlide upload).
- **Testing:** automated tests use a mock server. Real uploads are a manual test with the pilot's own account, run sparingly because WeGlide describes its API as not finalised.

## Appendix F: Project setup and tests

- **Identity:** the app name is SoarLog and the package ID is `io.github.jgfyyvzss.soarlog`. Both were renamed on 2026-10-23 from soarlogs and `io.github.jgfyyvzss.gliderpilotlog` (confirmed 2026-10-19), with the GitHub repository, now `Jgfyyvzss/SoarLog`. The new package ID installs as a separate app, so the logbook moves across with Back up and Restore.
- **Release APK name:** a release build also saves its APK as `app/build/outputs/named_apks/release/SoarLog-<versionName>-release.apk`, through an `androidComponents.onVariants` task in `app/build.gradle.kts` (AGP 9 removed the old rename; added 2026-10-23).
- **Android versions:** propose a minimum of API 26 (Android 8.0), which has the java.time classes built in, pending the decision listed below. Use the current stable compile and target versions at project start.
- **Stack:** Kotlin, Jetpack Compose with Material 3, Navigation Compose, Room with KSP and its Paging integration, DataStore (Preferences), WorkManager for scheduled exports, kotlinx.serialization for JSON, and OkHttp for HTTP. Use the latest stable releases at project start, pinned in a version catalogue, and check them against the official documentation. CSV needs only a small RFC 4180 reader and writer in the core package.
- **Layout:** one Gradle module with `core` (pure Kotlin, no `android.*` imports), `data` (Room, DataStore, files) and `ui` packages. A `testdata` folder holds the pilot's glider table, an anonymised flights CSV and a few real IGC files.
- **Permissions:** INTERNET, for WeGlide, and the Bluetooth permissions described in the LXNAV section. No CAMERA permission: the witness photo comes from the system camera app through a file-provider address in the app's cache. Files are read and written through the system document pickers, with a persisted tree permission for the backup folder, so no storage permissions are needed.
- **Scheduled backups:** after each saved change, if at least the set number of changes (default 20) or days (default 7) have passed since the last export, enqueue a one-time WorkManager job. Changes are counted from `updated_at`, so no counter is stored. The Home screen nudges when there are flights and the last backup is more than 7 days old or missing. Keep the last 10 files, named glider-logbook-YYYYMMDD-HHMMSS.json.
- **Display:** dates use the device locale's short format, durations use the Settings choice, Material dynamic colour is used, dark mode follows the system, and rotation must not lose form state. Touch targets are at least 48 dp.
- **Languages:** the UI is English for the first releases, with German and then French to follow, in that order. Every piece of text the pilot sees lives in string resources, plurals included. The `core` package returns message keys with arguments instead of English sentences, and the UI turns them into text. Roles are stored as their enum names and the seeded launch types are shown in the device language until the pilot renames them. Stored names (gliders, airfields, clubs, renamed launch types), the CSV column names and values, the JSON backup format and the IGC data stay the same in every language. Added 2026-10-21. With German added (2026-10-23), the build generates the language list from the `values-*` folders (`generateLocaleConfig`, default English named in `res/resources.properties`), so Android 13 and later offer the app in the system's per-app language setting. Android 12 and earlier follow the phone's language; an in-app picker for them is not built.

## Appendix G: GAus MOSP logbook requirements
7.9 Pilot personal logbook
7.9.1. All pilots must keep a personal logbook in which to record the following information:
(a) the person’s name and date of birth.
(b) the person’s Gliding Australia membership number.
(c) name(s) of pilot-in-command (PIC);
(d) date of flight;
(e) place of departure and arrival;
(f) type/model and registration of the sailplane;
(g) total time of flight;
(h) launching method
(i) accumulated total time of flight;
(j) details on pilot function, namely PIC including solo, dual or as Flight Instructor; and
(k) operational conditions (e.g. aerobatics).

**Tests**

- **Plain JVM:** duration arithmetic including overflow and the form's digit entry for durations and times, CSV parsing and the error messages, a JSON export-import-export round trip that must be identical, the IGC parser against the sample files, the WeGlide search normalisation and ranking, and for witness sign-off the seal against fixed test vectors, the sign-off checks, the photo scaling and orientation rules, the backup import checks and merge, and the CSV and PDF witness columns.
- **On a device or emulator:** the witness repository, the photo pipeline (upright, 200 pixels, within 25 KB, no location) and a backup round trip with and without photos; the Room constraints (unique `log_no` that ignores tombstones, foreign keys, partial indexes), and every migration with `MigrationTestHelper`, starting from a database that already has the partial indexes and triggers, so Room's schema validation is tested against them. The migration tests must pass before any release that changes the schema.
- **UI:** a smoke test that creates a flight, filters the list, and checks the totals.

## Later features

OGN import and an iOS port are not planned for the first releases, but the design leaves room for both.

- **OGN import:** Flightbook exposes a JSON API (its repository holds the documentation), but that documentation could not be read and its usage terms are unverified. KTrax offers a logbook API only to subscribers with an API key, limited to one automated query a minute. OGN identifies aircraft by device, so gliders would need an optional device ID. With no times stored, matching an OGN flight to a manual entry would rely on glider, date and duration, and same-length flights on one day would be left for the pilot to decide.
- **iOS:** the versioned JSON schema and multiplatform-capable libraries (Room, Compose) are the only preparation, and neither costs anything now.

## Build order

The first three steps give a usable logbook with safe backups before any integration is built.

1. **Schema and the flight form.** Done when the database creates with every constraint in Appendix A, airfields, gliders, clubs and launch types can be created and edited, a flight can be entered with every rule enforced (unique log number, mandatory launch type, the club rule, the pilot in command rules, H:MM duration entry with overflow), and the Room constraint tests pass.
2. **Flights list, filters, opening balances and totals.** Done when 10,000 generated flights scroll without noticeable delay, every filter works alone and combined, totals match a hand calculation with and without a filter, and the Settings choices for hours display, PIC counting, landing place and times work.
3. **JSON backup, restore and scheduled copies.** Done when an export, import and export round trip is identical, Restore into an empty database reproduces the original, Merge reports a log number collision, scheduled exports write timestamped files to the chosen folder, and first-run restore works.
4. **CSV export and import, including stubs and undo.** Done when the exports re-import cleanly, the pilot's prepared flights CSV and 14-glider table import, errors name their rows, and Undo last import removes the batch and its unused stubs.
5. **Aircraft logbook calculator.** Done when a day's flights sum correctly, unticking a flight updates hours and flights, the Other pilots row works, results match a hand calculation including overflow, the stored logbook total prefills and can be overwritten, Save new total writes the result and the flying day back, and the double-add warning appears.
6. **WeGlide aircraft list.** Done when the bundled list loads and searches, Update in Settings replaces it and keeps it on failure, picking an entry on a glider sets the ID and type after showing the change, the Gliders list marks gliders without a WeGlide type, the gliders export writes `weglide_aircraft_name`, and mock-server and fake tests cover success and failure. Moved ahead of IGC import so each glider's type is settled early.
7. **IGC import.** Done when the sample files give the logbook's date and duration to within a minute, airfield matching and the create prompt work, multi-segment files list their segments, the form is pre-filled, and an IGC file shared from another app opens the flow.
8. **WeGlide upload.** Done when mock-server tests cover upload success and failure, the upload gate prompts for anything missing, and one manual upload with the pilot's own account succeeds.
9. **LXNAV flight download.** Done when the Android transport connects to the pilot's S100, the flight list matches what the logger shows, a downloaded file has the same size as the logbook entry and the same hash as a reference copy, an interrupted transfer (the logger switched off mid-download) resumes or fails cleanly, the fake-transport tests pass, and the file opens in the IGC import flow as From logger. Start with the connection test described in its section.
10. **Logbook PDF.** Done when the Simple list, Compact and GFA-style templates print the exported flights with page totals, brought forward and carried forward matching the on-screen totals, a filtered export holds only the filtered flights and says so, a 10,000-flight export completes in the background with progress and cancel, and a template imported from a file works. Started 2026-10-23, ahead of OGN.
11. **Witness sign-off.** Optional, behind a Setting that is off by default. Designed in `docs/Witness_Sign-Off.md`, adopted 2026-10-23; done when that document's nine build-step criteria pass. It extends the JSON backup (format version 2) and the CSV export, so it follows those steps. Built 2026-10-23 on the `Witness` branch in seven parts. The JVM tests for parts 1 to 4 pass; those for the backup (part 5) and the CSV and PDF (part 6) are still to be run. The device tests do not run on the pilot's phone, so the photo pipeline, the repository and the backup round trip are checked by hand; restoring a photo-less backup and then merging a full one brought the photos back (pilot, 2026-10-23).
12. **OGN import.** Not to be started until it has its own specification.

## Decisions and open points

All five points have been settled.

1. **Totals:** Instructor and Coach flights - selectable in Settings if they also count toward PIC, or stay separate with Dual as their sum. The Totals screen shows the five buckets and Combined.
2. **Landing place:** Optional, behind the Record landing place Setting, off by default. Launch and landing times are likewise optional, behind a separate Record launch and landing times Setting, off by default.
3. **Aircraft logbook rounding:** whether the logbook wants H:MM or decimal hours, and any rounding rule. Selectable in settings, rounding to nearest minute.
4. **Launch type list:** the seed values = Aero, Winch, Self, Auto
5. **Minimum Android version:** API 26 (Android 8.0), confirmed 2026-10-19.

## Sources consulted

Research ran on 2026-10-18; several pages were read as search extracts, not in full.

- [Add Times (Akaflieg Freiburg)](https://github.com/Akaflieg-Freiburg/addhoursandminutes)
- [KTrax API usage](https://ktrax.kisstech.ch/api-usage)
- [WeGlide developer docs](https://docs.weglide.org/creators/developers.html)
- [WeGlide flight upload](https://docs.weglide.org/how_to/flight_upload.html)
- [WeGlide: getting started](https://magazine.weglide.org/getting-started/)
- [WeGlide Tasks in XCSoar](https://magazine.weglide.org/weglide-tasks-xcsoar/)
- [XCSoar 7.23 release notes](https://xcsoar.org/news/2022/02/11/xcsoar-7-dot-23-released.html)
- [Room release notes](https://developer.android.com/jetpack/androidx/releases/room)
- [Compose Multiplatform and Jetpack Compose](https://jetbrains.com/help/kotlin-multiplatform-dev/compose-multiplatform-and-jetpack-compose.html)
- [Android SQLite performance best practices](https://developer.android.com/topic/performance/sqlite-performance-best-practices)
- [Android Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)
- [Auto Backup for Apps](https://developer.android.com/identity/data/autobackup?authuser=2)
- [aerofiles IGC header examples](https://aerofiles.readthedocs.io/en/latest/guide/igc-writing.html)
- [LXNAV Data Port Specification 1.04 (Draft, Internal)](https://github.com/XCSoar/XCSoar/files/11738481/LXNAV_DataPort_Specification1.04.pdf)
- [XCSoar 7.44 release notes](https://xcsoar.org/news/2026/03/22/xcsoar-7-dot-44-released.html)
- [XCSoar documentation index](https://xcsoar.org/docs/)
- [Android Bluetooth permissions](https://developer.android.com/guide/topics/connectivity/bluetooth/permissions)
