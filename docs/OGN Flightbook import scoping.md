# OGN Flightbook import: scoping

Nov 10, 2026 · @Peter

## Goal and workflow

An OGN button on Home fetches one day's glider flights at an airfield from OGN Flightbook. The pilot picks their own flights, reviews them as they will appear in the Flights list, and writes them to the logbook. The request carries only an airfield code and a date; like any web request, the server also sees the phone's IP address.

1. **Home:** a new OGN button beside From IGC and From logger.
2. **OGN search:** airfield (from the Airfields table, or add one on the spot), date (default today), optional glider (from the Glider table, or typed).
3. **Results:** the day's flights, most recent at the top as the Flights page shows them, each with glider, times, duration and a tick box. Tow plane flights hidden by default.
4. **Review and edit:** the ticked flights shown as Flights-page rows, each opening the flight form prefilled, with launch type, role, pilot in command and notes filled in or defaulted.
5. **Create:** writes the ticked rows as flights, then returns to the flights list.

## What the Flightbook API provides

The API is documented in the project's API.md, which you supplied. Two GET endpoints return one airfield's day as JSON, with no key: `/api/logbook/<code>` (the airfield's current date) and `/api/logbook/<code>/<yyyy-MM-dd>`. The code is an ICAO code, upper or lower case. API.md does not name the host. The only evidence is [a third-party bot](https://git.oranki.net/jarno/hemppa/raw/branch/helpfix/modules/flog.py) that uses `https://flightbook.glidernet.org`, and a real call to that host on 10 November 2026 succeeded.

The API's own warning: a high daily call rate from one client may be blocked by the maintainer. The app makes one request per search, caches the result for the session, and sends no background or repeat calls.

Top level: `airfield` (`code`, `country`, `name`, `elevation`, `latlng`, and `time_info` with `tz_name` and `tz_offset`), `date` (the date at the airfield, in the airfield's time zone), `devices[]`, `flights[]`. Name, elevation and position are null when Flightbook does not hold the airfield.

Each `devices[]` entry: `registration`, `competition`, `aircraft` (type text), `aircraft_type` (1 glider or motor-glider, 2 tow plane, 3 to 6 other), `address`, `identified`. Aircraft opted out of tracking are never returned. Unregistered or unidentified aircraft get a random address starting `~` or `_` and no registration.

| Flight field | Meaning | Use in the app |
| --- | --- | --- |
| `device` | Index into `devices` | Glider and type; the competition ID is shown, not matched |
| `start`, `stop` | Local time at the airfield as `HH'h'mm`, or null | Launch, Landing times |
| `duration` | Seconds, null when no landing was seen | Duration |
| `start_delta`, `stop_delta` | Minutes of uncertainty when takeoff or landing was not seen exactly | Mark the time as approximate |
| `tow` | Index of the tow plane's flight, or null | Launch type Aero |
| `towing` | True for a tow plane's own flight | Hidden by default |
| `start_code` | Present when the flight took off at another airfield (ICAO) | Launch airfield |
| `stop_code` | Present when it landed at another airfield (ICAO) | Landing airfield |
| `warn` | True when not detected landed and not formally seen on the ground at a known airfield | Flag for review |
| `duration_expected` | Today only: expected duration of a flight still in the air | Shown, not stored |
| `max_alt`, `max_height` | Metres: the flight's highest point, above sea level and above the airfield | A tug's `max_height` becomes the towed glider's AeroTow note; otherwise not used |

Confirmed on 10 November 2026 from a real response (YMBT, 2026-11-09, four glider flights):

- The host `flightbook.glidernet.org` works, and Flightbook holds YMBT: name Mount Beauty, elevation 334 m and position -36.72895, 147.1671 all came back, matching your airfield data. Its `tz_name` is Australia/Sydney, the same +11:00 offset.
- The response differs from API.md in small ways: the day key is `a_day`, not `a-day`; there is a top-level `code`; devices carry a `tracked` field. The parser must ignore unknown keys and tolerate missing optional ones. This response is the first test fixture.
- `start` and `stop` are the real times cut down to the minute, not rounded. Durations are exact seconds. In two of the four flights, `stop` minus `start` is one minute longer than the duration rounded to a whole minute (263 and 262, 301 and 300).
- Registrations are not uniform: one aircraft has `GVS`, the others `VH-GJF`, `VH-GVA`, `VH-GMK`. Matching to your glider list has to cope with a missing `VH-` prefix.
- One flight has `start_delta` 654. Above 60 the takeoff time is not adjusted and the real takeoff could be up to that many minutes earlier, so that flight's launch time and duration cannot be trusted.
- No tow plane flights or `tow` links appeared that day, so `tow` null does not mean the launch was not an aerotow.

Confirmed from a second real response, YTEM (Temora) on 2026-11-08, an aerotow day saved as `testdata/Flightbook_YTEM_2026-11-08.json` (test fixture 2):

- 22 flights: 11 by one tug (PA-25 Pawnee, `aircraft_type` 2, `towing` true) and 11 glider flights, each with `tow` pointing to its tug flight, which starts in the same minute. One `tow` index points to a later entry in the array, so links must be resolved after reading every flight.
- `max_height` is the flight's maximum height above the airfield, not a launch height: gliders reach 932 to 2519 m. A tug's `max_height` (487 to 690 m) is close to the release height of the glider it towed, and its `max_alt` (764 to 967 m, with YTEM at 277 m) the release altitude above sea level.
- `warn` is true on all five Janus B flights, which were ordinary local flights, so `warn` is not a sign of an outlanding.
- One registration is `VHUKB`, with no hyphen. Ignoring hyphens covers it; with `GVS` at YMBT, that is two real formats besides `VH-XXX`.
- No `start_delta`, `stop_delta`, `start_code` or `stop_code` appear. Those cases, an unidentified aircraft and an empty day still need real responses.

## Screens

Three screens, reusing existing pickers, validation messages and the airfield form.

**1. OGN search**

- **Airfield:** a picker over the whole Airfields table, with "+ New airfield" at the top as elsewhere. Picking an airfield with no ICAO code asks for the code and saves it on that same record, so no second record appears.
- **"+ New airfield":** enter a code, and the search runs. If Flightbook returns the airfield's name, position and elevation, the new-airfield form opens prefilled with them and the code. Before saving, the app checks the table for the same code, then for a code-less airfield within its match radius, and offers to add the code to that record instead.
- **Date:** today by default, one day per search. No previous or next buttons; a new search covers another day.
- **Glider (optional):** pick from the Glider table or type a registration. It filters the fetched flights on the phone and does not change the request.
- **Search:** shows progress, then the results. Errors are plain messages with Try again.

**2. Results**

- One row per flight: glider registration and type, start to stop, duration, tick box. Nothing starts ticked. Most recent at the top, as on the Flights page.
- Tow plane flights are hidden, with a switch to show them and other aircraft types.
- Badges: Already in logbook, No landing time, Time approximate (with the delta), Launched at XXXX, Landed at XXXX, Glider not in your list, and Unidentified aircraft.
- Select all, Select none, and a count; Review is enabled once at least one is ticked.

**3. Review and edit (decided 2026-11-10: preview rows).** The ticked flights are shown as Flights-page rows, in the same three-line layout, marked as not yet saved, so what you see is how they will look once saved. The Flights page is not a table of editable cells, so editing reuses the flight form: tapping a row opens it prefilled, with every field and all its checks, as IGC import does. A bar above sets launch type, role or club for every row at once. A row with a problem the form would report (such as a missing club or launch type, or an unconfirmed time) is marked, and Create stays disabled until none remain. The button states how many flights it will add and their log numbers.

## Mapping OGN data to flight records

Airfields get two naming fields: `name` (plain text, such as Mount Beauty), required and unique as today, and `code` (optional). The code is usually the ICAO code, but a small private field may have an unofficial one, or none until it gets one later; any text is accepted. When present, the code is unique among live airfields, ignoring case, so a search by code finds one airfield. Both rules are enforced by database indexes (decision 8). A search only finds flights for a code Flightbook knows. The airfields CSV, the JSON backup and `cup_to_airfields.py` gain the `code` column, and the script writes name and code separately instead of `YMBT - Mount Beauty`.

| Logbook field | Comes from | Rule |
| --- | --- | --- |
| Glider | `devices[].registration` | Compare ignoring case, hyphens and spaces; then compare the part after the first hyphen, on either side, so `GVS` finds `VH-GVS` and `VH-GVS` finds `GVS`. The competition ID is not matched: gliders have no such field. One candidate is matched. Several, or none: pick by hand, or create a stub glider using OGN's `aircraft` text, completed later, as in CSV import. |
| Date | The searched date | The date at the airfield, as the API gives it |
| Airfield | The searched airfield, or the `start_code` airfield when the flight took off elsewhere | Matched by code; an unknown code must be chosen or created |
| Launch, Landing times | `start`, `stop` | Only when the times setting is on. Already local time at the airfield, so stored as given. |
| Duration | `stop` minus `start` when times are recorded, otherwise `duration` | With times recorded, the difference of the two stored times, which avoids the flight form's warning that duration and times disagree (the API's minute-cut times can otherwise disagree with its seconds by one minute). Without times, `duration` rounded to the nearest minute, at least 1. Null or over 24:00: flagged. |
| Time reliability | `start_delta`, `stop_delta` | Up to 60 minutes: marked approximate. Above 60: Check time badge, must be confirmed before Create. |
| Launch type | `tow` link, else airfield default | Aero when a tow plane is linked; otherwise the airfield's default; if neither, it must be chosen |
| Landing place | `stop_code` airfield, else Same as departure | Only when the place setting is on, as today (decision 11). `warn` does not mark an outlanding (see the YTEM findings), so a flight without `stop_code` defaults to Same as departure and the pilot corrects an outlanding by hand. |
| Notes, landed elsewhere | `stop_code` | Only when the place setting is off: "Landed at YXXX" with the `stop_code`. Otherwise empty. |
| Notes, tow height | The linked tug flight's `max_height` | For an aerotow with a `tow` link only: "AeroTow 640 m (2100 ft) AGL, OGN" (wording decided 2026-11-10), metres rounded to 10 and feet to 50. It is the tug's highest point above the airfield, close to the release height. The Help file explains that it comes from the tug's track and GPS, so it is an estimate. No launch height field is added (decided 2026-11-10); pilots can write the same in the notes of a manual entry. |
| Club | Airfield default | Editable. The app requires a club whenever the airfield has any, so an airfield with clubs but no default leaves the row needing a club before Create. |
| Role, Pilot in command | PIC, and the profile's pilot name | OGN knows aircraft, not people. Editable per row or in the bulk bar. |
| Log no. | Next free numbers | Assigned in start-time order, checked for uniqueness again at Create. Existing flights are never renumbered (decision 12). |
| Notes | Empty, apart from the two notes above, joined with "; " | The glider's own maximum altitude is not used |

Other rules:

- **One record per airfield.** The table is searched by code first, then by position within the airfield's match radius, whenever OGN data would create or change an airfield. A hit is offered as "Use this airfield and add the code", and a new record is made only when the pilot declines. Nothing is merged silently.
- **Already logged:** a flight is flagged when your logbook has one with the same glider and date and either a launch time within 5 minutes or, with no times recorded, a duration within 2 minutes. It starts unticked, as every row does, and can still be ticked. Nothing is blocked.
- **Undo:** created flights are recorded as an import batch with source OGN, so the existing Undo last import covers them.
- **Earlier than the logbook:** when any ticked flight is dated before the newest flight already logged, the review screen warns, for example "These flights are earlier than No. 341 (9/11/26) and will be numbered after it", with Continue and Cancel. Nothing is renumbered.

## Edge cases and failure handling

- **Day boundary.** The API's date and all its times are local to the airfield, so a morning flight in eastern Australia is listed on the right day. Nothing extra is shown, to save screen space (decision 6); the Help file explains that the date is the airfield's local date.
- **No network, timeout, an HTTP error, or being blocked.** One request per search, a 15 second timeout, a plain message and Try again, never an automatic retry loop. The search is kept for the session so Back does not refetch. A refusal from the server says the service declined the request, not that the app is broken.
- **Airfield unknown to Flightbook.** Name, position and elevation come back null. The search still shows any flights, and the new-airfield form is not prefilled.
- **Empty result.** Say so without implying a fault: the airfield may have no OGN receiver coverage, or nobody flew.
- **Missing flights.** Aircraft opted out of tracking are never returned, so the results screen says it lists tracked aircraft only.
- **Unidentified aircraft.** A null registration or an address starting `~` or `_` gives no glider match. The row shows the aircraft and competition text, and the pilot picks the glider.
- **No landing time.** A flight with no `stop` has no duration. It is flagged, and the pilot enters the duration or unticks it. For today's flights `duration_expected` is shown as a hint but never stored.
- **Launched or landed elsewhere.** `start_code` and `stop_code` set the launch or landing airfield. A code that is not in the table must be chosen or created before Create.
- **Outlanding.** API.md sets `stop_code` only for a landing at an airfield Flightbook knows, so a paddock outlanding has no code, and one out of receiver range gives no `stop`. `warn` cannot tell an outlanding apart: at YTEM all five local Janus B flights have `warn` true. An outlanding without a code is therefore not detected; the pilot corrects the landing place or notes by hand.
- **Doubled entries.** An aircraft with two trackers may appear twice for one flight. Rows for the same glider with starts within 2 minutes are marked as likely duplicates.
- **Not your flight.** The pilot is not identified, and several pilots can fly one glider in a day. The pilot chooses by time.
- **Different from your own record.** OGN detects takeoff and landing from speed, so times and durations can differ by minutes from a logger or a stopwatch. Every value is editable.
- **Create fails part-way.** All rows are written in one database transaction, so either every ticked flight is created or none.
- **Log number clash at Create.** If another flight takes a number during review, renumber the remaining rows and say so.
- **Permissions.** Internet access is already used for WeGlide, so no new permission.

**Unreliable time.** A `start_delta` or `stop_delta` of 60 minutes or less means the time is already the median of a range, so it is marked approximate. Above 60 it is not adjusted: the row shows a Check time badge with the figure, and the pilot must confirm or correct the time before Create.

## Decisions and open points

Decided on 10 November 2026:

- The code is an ICAO code. Airfields get `code` and `name` as two fields.
- One day per search, no previous or next buttons. Nothing starts ticked.
- Tow plane flights are hidden. They default Launch type to Aero, and their maximum height above the airfield goes in the glider flight's notes as "AeroTow … AGL, OGN" (added 2026-11-10; briefly the altitude above sea level, changed the same day because Flightbook gives both). The glider's own maximum altitude is not used.
- No launch height field is added to flights (2026-11-10). Pilots record it in the notes, as OGN imports do for aerotows.
- Already-logged thresholds: launch time within 5 minutes, or duration within 2 minutes when no times are recorded.
- Created flights are recorded as an import batch so Undo last import covers them. The app sends an identifying User-Agent, and the three import buttons stay on Home.
- The review shows Flights-page rows that open the flight form, rather than a table of editable cells.
- Results show the most recent flight at the top, as the Flights page does. The glider is matched by registration only, and role and pilot in command default to PIC and the profile name.

Open points, each with a recommendation:

| # | Point | Outcome |
| --- | --- | --- |
| 1 | Existing airfield names like `YMBT - Mount Beauty` | Decided: no in-app split helper (confirmed 2026-11-10). Peter edits the airfields in a CSV outside the app and reimports. Code must make CSV import and export carry separate `code` and `name` columns, and state clearly what happens when a row matches an existing airfield (see the merge rules above). |
| 2 | Code uniqueness | Decided: unique among live airfields, ignoring case. CSV import skips a row whose name or code already exists and reports it. |
| 3 | JSON backup gains `code` | Superseded by decision 9: the backup format moves to version 4. Older backups restore with blank codes. |
| 4 | User-Agent | Decided: `SoarLog/<version>`, the same name the app sends to WeGlide. |
| 5 | Reuse of the Flights page component in editable mode | Decided 2026-11-10: no. The Flights page is three-line rows that open the flight form, not a table, so the review shows those rows and each opens the form prefilled. |
| 6 | Date and time zone default | Decided: today's date, nothing extra shown, to save screen space. The Help file explains that the date is the airfield's local date. |
| 7 | Duration when times are recorded | Decided: stop minus start as stored, which avoids the flight form's warning that the two disagree. |
| 8 | Name and code uniqueness | Decided 2026-11-10: the name is required and unique, as today; the code is optional, may be unofficial, and is unique when present. Both are enforced by partial unique indexes on live airfields, as `log_no` is: database version 5, with Restore, Merge and CSV import reporting a clash instead of creating one. |
| 9 | Backup format version for `code` | Decided 2026-11-10: bump to version 4. The 3 to 4 upgrade only sets the version, so an older app refuses a newer backup with a clear message instead of dropping the codes. |
| 10 | Flight source OGN | Decided 2026-11-10: add OGN as a source, in the same version 4. |
| 11 | Landing place with the place setting off | Decided 2026-11-10: keep today's rule, stored only while the setting is on. With it off, the notes say "Landed at YXXX" when there is a `stop_code`. "Landed at other" for `warn` true was dropped the same day: the YTEM fixture shows `warn` true on ordinary local flights. |
| 12 | Log numbers in date order | Decided 2026-11-10: warn, with Continue or Cancel, when imported flights are dated before the newest flight logged. They take the next numbers in start-time order; nothing is renumbered, because renumbering existing flights would break witness seals and the match with the paper logbook. |

## Build steps and acceptance criteria

Build in this order. Every open point is now decided; step 2 waits for Peter's further real responses.

1. **Airfield code.** Add the optional `code` field and the two partial unique indexes (database version 5), airfields CSV import and export, the backup (format version 4, which also allows source OGN) and `cup_to_airfields.py`. There is no in-app helper to split existing `CODE - Name` names (decision 1). Older backups and CSV files must still import.
   - **As built (2026-11-10, `OGN` branch, not yet run):** `Keys.airfieldCode` stores a code trimmed and in capitals, so a plain unique index on live rows makes codes unique ignoring case, and a null code never clashes. `MIGRATION_4_5` adds the column and drops the flight triggers, which the open callback recreates with source OGN. The airfield form has a Code field, the list shows "YMBT · Mount Beauty", and saving refuses a code another airfield uses. airfields.csv gains a `code` column after `name`; a row whose name or code exists is skipped and reported. The backup's 3 to 4 upgrade only sets the version, and two live airfields with one code refuse the file. The script now writes name and code separately by default (`--name-format code-name` keeps the old names). Tests: `CsvTest` and `BackupCodecTest` (codes, the upgrade, source OGN).
   - **Not covered:** CSV import never updates an existing airfield, so a reimported CSV cannot rename `YMBT - Mount Beauty` to `Mount Beauty` with code YMBT (see the question raised 2026-11-10).
2. **API client and parser** in plain Kotlin, with no Android types, tested against saved real responses, starting with the YMBT 2026-11-09 response, kept in the repository as a fixture. YTEM 2026-11-08 (fixture 2) covers `tow` links and tug flights. Peter is supplying further real responses covering a null stop, `start_code` and `stop_code`, an unidentified aircraft, two trackers on one flight and a day with no flights; none is to be invented.
   - **As built (2026-11-10, `OGN` branch, not yet run):** `core/ogn/Flightbook.kt` holds `OgnDay`, `OgnFlight`, the `Flightbook` interface and `FlightbookParser`, which ignores unknown keys, tolerates missing optional ones, reads `HH'h'mm` times, and resolves each `tow` link after reading every flight, giving the glider its tug's flight and the tug's `max_height` as `towHeightM`. `data/ogn/OkHttpFlightbook.kt` sends one `GET https://flightbook.glidernet.org/api/logbook/<code>/<date>` with User-Agent `SoarLog/<version>` and a 15 second limit, encodes the code as one path segment, never retries, and turns 403 and 429 into "declined", other statuses, no network and an unreadable reply into plain messages. Fixture 1 is now the file `testdata/Flightbook_YMBT_2026-11-09.json`, copied unchanged from the appendix. Tests: `FlightbookParserTest` (both fixtures) and `FlightbookClientTest` (a local mock server; no test calls Flightbook).
   - **Still to come:** tests for a null stop, `start_code` and `stop_code`, an unidentified aircraft, two trackers and an empty day, when those real responses arrive. Whether Flightbook accepts the plain `SoarLog/<version>` User-Agent is only known from a first real call in the app; WeGlide refused a similar one.
3. **Search screen** with the airfield and glider pickers, the code prompt for an airfield without one, and the new-airfield path with position matching.
   - **As built (2026-11-10, `OGN` branch, not yet run):** `ui/ogn/OgnSearchScreen.kt`. The airfield picker lists airfields as "YMBT · Mount Beauty". Picking one without a code asks for it and saves it on that record (`LogbookRepository.setAirfieldCode`, which refuses a code another airfield uses). "+ New airfield" asks for a code; a code already in the table selects that airfield, otherwise the day is fetched, and a code-less airfield whose match radius contains Flightbook's position is offered as "Use and add code", nearest first. Declining, or no such airfield, opens the airfield form prefilled with the code, name, position and elevation Flightbook gave; once it is saved the search selects it. The position check happens before the form opens, not at its Save. Date defaults to today. The glider field takes a typed registration or one picked from the gliders, and only filters (`OgnMatching.sameRegistration`). `data/ogn/OgnSession.kt` keeps the search and every fetched day for the app session; a failure is not kept, so Try again asks again. Until step 4, a found day shows only its glider-flight count (and the filtered glider's count), or the empty-day message. Strings are in `strings_ogn.xml`, English and German. Tests: `OgnMatchingTest`, `OgnSessionTest`.
   - **Ahead of step 6:** the From OGN button on Home and the search route are added now, so the screen can be tried on the phone.
   - **Tried on the phone (2026-11-10):** a search found 2 glider flights, which looked right, so Flightbook accepted the `SoarLog/<version>` User-Agent.
4. **Results screen** with ticking, badges, the tow plane switch and the already-logged check.
   - **As built (2026-11-10, `OGN` branch, not yet run):** `ui/ogn/OgnResultsScreen.kt`, with the rules in `core/ogn/OgnImport.kt`. A successful search opens the results; Show flights on the search returns to them. Rows are most recent first, nothing ticked, glider flights only until the switch shows the rest, narrowed by the search's glider. Badges: Tow plane, Already in logbook, No landing time (with Expected for today), Time approximate ±N min (deltas up to 60), Check time up to N min (above 60), Launched at, Landed at, Glider not in your list, Several gliders match, Unidentified aircraft, Likely duplicate (same registration, starts within 2 minutes). Select all ticks the visible rows; ticks are kept per day for the session.
5. **Review screen**: Flights-page rows that open the flight form prefilled, with defaults, the bulk bar, the flight form's validation, glider stubs and the single-transaction Create with import batch.
   - **As built (2026-11-10, `OGN` branch, not yet run):** `ui/ogn/OgnReviewScreen.kt`. Review numbers the ticked flights from the next free log number in start order and builds each draft with `OgnImport.draft`: date, times only with the times setting (duration then landing minus launch, else Flightbook's seconds rounded, at least 1), the searched airfield or the `start_code` one, the club when the airfield has one or a default, Aero when there is a tow link (else the airfield default), landing airfield from `stop_code` only with the place setting, role PIC and the profile name, and the notes. A registration not in the glider list becomes a stub glider, created at Create as incomplete with OGN's type text; several matches or an unidentified aircraft leave the glider to pick. Rows reuse the Flights-page `FlightRow`, marked Not yet saved or Needs attention, with each problem listed: the flight form's own validation, a log number repeated within the import, and an unconfirmed Check time. Tapping a row opens the flight form in a review mode (`ReviewDraft`): it checks as usual and Save returns the draft to the review, which also confirms Check time. The bar sets launch type, role (adjusting the pilot in command as the form does) and club (only on rows whose airfield has that club; shown when the rows' airfields have more than one club). Create warns first when any flight is dated before the newest logged flight, then `LogbookRepository.createImportedFlights` validates every row and writes them, the stub gliders and one import batch named `OGN <code> <date>` in one transaction; if a number was taken meanwhile, all rows are renumbered from the next free number and a dialog says so. Then the Flights tab opens. Undo last import removes the batch and its unused stub gliders.
6. **Home button**, navigation and string resources. All text goes in resources, with the English file first and the German file after review.
   - **As built (2026-11-10):** the Home button and routes (search, results, review, and the flight form's `ognRow`) are in `SoarLogRoot.kt`. English and German strings are in `strings_ogn.xml` and `strings_messages.xml`; the German is a draft for review, including "F-Schlepp … AGL, OGN" and "Gelandet in". The Help file has a From OGN section. Tests: `OgnImportTest` (both fixtures) on the JVM, and `OgnCreateTest` as a device test (batch, stubs, renumbering, all-or-nothing, Undo), which cannot run on the pilot's phone.

Acceptance criteria:

- A search sends only the airfield code and the date, with the identifying User-Agent, and makes one request.
- A day's flights at an airfield I know appear within a few seconds, with tow plane flights hidden and nothing ticked.
- Searching with an airfield that has no code asks for it and saves it on that record. No second record is created for an airfield already in the table, whether found by code or by position.
- Ticking three flights and tapping Review shows three rows laid out as the Flights page lays out flights, each opening the flight form with exactly the values that will be stored.
- Create is disabled while any row has a problem, and creating writes all rows or none.
- Log numbers stay unique and continuous, and no existing flight is renumbered. Importing a day earlier than the newest logged flight warns first. A flight already in the logbook is flagged and unticked.
- Times are stored as local times at the airfield, and only when the times setting is on.
- Undo last import removes exactly the flights an OGN session created.
- Offline, an empty day, an unknown code and a refused request each show a clear message and leave the app usable.

## Sources

Pages I opened, as of 10 November 2026:

- [hemppa flog.py](https://git.oranki.net/jarno/hemppa/raw/branch/helpfix/modules/flog.py): the endpoint and the fields in the API table.
- [hemppa commit 1667576](https://git.oranki.net/jarno/hemppa/commit/166757674bee1b3e87324fd02a9fd5d1f9424922): the switch to the Flightbook endpoint, and the fields `seq`, `competition`, `aircraft`.
- [OGN Flightbook API.md](https://gitlab.com/lemoidului/ogn-flightbook/-/blob/master/doc/API.md): the official document, read from the copy you pasted on 10 November 2026.
- [OGN Flightbook](https://flightbook.glidernet.org/): a JavaScript page, so nothing could be read from it.

## Appendix: OGN Flightbook API documentation

The text of the project's API.md, copied as supplied so Code does not need to fetch it. Where the real response of 9 November 2026 differs (extra `a_day`, top-level `code` and per-device `tracked` fields), the Confirmed section above takes precedence. Note the documented `a-day` and `call_tsp` keys; the real response used `a_day`.

```
API Documentation
Daily high call rate for same client may be blocked by maintener.
If you want to use the api in a real time manner, have a look on Igcd and particulary the webhook part.
Webhook are much more efficient than high frequency polling.

/api/logbook/<airfield-code>
Provide logbook for the given airfield ICAO code and actual airfield date
Method: GET
Protocol: HTTP or HTTPS
Parameters:
  airfield-code: string with ICAO code upper or lower case

Response: JSON
Response Code: 200
Response description
{
 "airfield": {json_airfield_object}
 "date": string,        --> date now for corresponding airfield tz. format yyyy-MM-dd
 "a-day": string,       --> abbreviated day (Mon, Tues, ...) for corresponding date
 "call_tsp: int,        --> Unix TimeStamp for api call
 "devices": [{json_device_object}, ] or empty array
 "flights": [{json_flight_object}, ] or empty array
 "rnames": [string, ] or empty array   --> array of OGN receivers name use for corresponding date
}

json_airfield_object
{
 "code": string,                  --> ICAO code provided in api request
 "country": string,               --> ISO 3166-1 identifier of the country
 "elevation": int or null,        --> elevation in meters if airfield is recorded in working instance, null otherwise
 "latlng": [float, float] or null,--> lat & lng of airfield position if airfield is recorded, null otherwise
 "name": string or null,          --> airfield name if airfield is recorded in working instance, null otherwise
 "time_info": {json_time_info_object}  --> time_info object if airfield is recorded in working instance
}

json_time_info_object
{
 "tz_name": string,     --> time_zone name
 "tz_offset": string,   --> time_zone UTC offset
 "dawn": string,        --> dawn local time rounded to nearset minute, format: HH'h'mm
 "sunrise": string,     --> sunrise local time rounded to nearset minute, format: HH'h'mm
 "noon": string,        --> noon local time rounded to nearset minute, format: HH'h'mm
 "sunset": string,      --> sunset local time rounded to nearset minute, format: HH'h'mm
 "twilight": string    --> twilight local time rounded to nearset minute, format: HH'h'mm
}

date
String date format as yyyy-MM-dd.
Date is calculated with time zone corresponding to airfield location

json_device_object
{
 "address": String,         --> address of beacon (eg: "DDE42F)
 "device_type": String      --> description below
 "db_org": String or null,  --> devices database origin: for now, just "OGN", null if not registered
 "aircraft": String or null,--> aircraft if found in database (eg: "Blanik), null otherwise
 "aircraft_type": int,      --> int [1..6], description below
 "competition": String or null, --> competition Name if found in database (eg: "MW), null otherwise
 "identified": Boolean,     --> True if identified option is activated in database, False otherwise
 "registration": String or null, --> ICAO aircraft registration if found in database (eg:"CC-AMW"), null otherwise
}

device_type: in accordance with OGN database:
 FLARM = "F"
 OGN TRACKER = "OGN"
 ICAO = "ICAO"

aircraft_type: in accordance with OGN database:
 GLIDER / MOTO-GLIDER = 1
 TOW / PLANE = 2
 ULTRALIGHT = 3
 HELICOPTER = 4
 DRONE / UAV = 5
 OTHERS = 6

Special note on Privacy concerns
If aircraft is not registered in any database, for privacy concerns beacon address is randomly generated.
Generated string value will be 11 chars long and prefix with '~' char
If aircraft is registered in OGN database with Identifed flag to False, beacon address is randomy generated.
Genererated string value will be 11 chars long and prefix with '_' char
If aircraft is registered in OGN database with No tracked option activated, aircraft will not be part of response api.

json_flight_object
{
 "device": int,            --> index of associated device json_object in devices array
 "duration": int or null,  --> if start & stop are not null, flight duration in seconds. null otherwise
 "duration_expected": int  --> optional property, see below
 "max_alt": int or null,   --> maximum altitude in meters reach during flight if start & stop are not null, null otherwise
 "max_height": int or null,--> (max_alt - airfield elevation)
 "start": String or null,  --> takeoff time formated as HH'h'mm acording to time zone, null if takeoff not detected
 "start_tsp": int or null, --> takeoff time in Unix TimeStamp, null if takeoff not detected
 "start_code": String      --> optional property, see below
 "start_q": int or null,   --> qfu (eg: 26) use during takeoff, null if takeoff not detected
 "start_delta": int        --> optional property, see below
 "stop": String or null,   --> landing time formated as HH'h'mm acording to time zone, null if landing not detected
 "stop_tsp": int or null,  --> landing time in Unix TimeStamp, null if landing not detected
 "stop_code": String       --> optional property, see below
 "stop_q": int or null,    --> qfu (eg: 26) use during landing, null if landing not detected
 "stop_delta": int,        --> optional property, see below
 "tow": int or null,       --> index of associated flight json_object in flights array if aircraft is towed, null otherwise
 "towing": Boolean,        --> true if flight is detected as a towing flight
 "warn": Boolean           --> see below
},

warn property
If aircraft is not detected as landed but formally detected on ground on kwnown airfield location, warn is false. True otherwise.

optional duration_expected property
If start is not null && stop is null (ie: duration is null) && logbook's date corresponding to today date, duration_expected is given.
duration_expected is given in seconds and represents the expected flight duration.

optionals start_delta or stop_delta property
If not exact time detection was possible for takeoff or/and landing, start_delta or/and stop_delta are added to flight object.
start_delta / stop_delta are given in minutes.
If start_delta / stop_delta < 60, time is already adjusted on median value. ie: start=13h20 and start_delta=10 => 13h10 < start < 13h30.
If start_delta / stop_delta > 60, time is not adjusted. start_time / stop_time could be delta minutes before.
In all case, duration is the diffence between stop_time and start_time (delta_time on duration = start_delta + stop_delta).
Too many start_delta / stop_delta for the same airfield signaling a bad reception on ground.
Too many start_delta / stop_delta for the same aircraft signaling weak emission for aircraft beacon.

optionnal stop_code porperty
If aircraft is detected landed in another airfield, additional stop_code is added on flight object.
stop_code value is the ICAO airfield code.

optional start_code porperty
If aircraft takeoff in another airfield, additional start_code is added on flight object.
start_code value is the ICAO airfield code
start_code and stop_code properties are mutually exclusive.

/api/logbook/<airfield-code>/<date>
Provide logbook for the given airfield ICAO code and given date
Method: GET
Protocol: HTTP or HTTPS
Parameters:
  airfield-code: string with ICAO code upper or lower case
  date: string date formated as yyyy-MM-dd

Response: JSON
Response Code: 200
Response description
Same as /api/logbook/<airfield-code> described above.
```

## Appendix: sample response (test fixture 1)

The real response from `GET /api/logbook/ymbt/2026-11-09`, fetched 10 November 2026, pretty-printed and otherwise unchanged. Four glider flights; the first has `start_delta` 654 (unreliable time) and the fourth `start_delta` 1; no tow links.

```
{
 "a_day": "Mon",
 "airfield": {
  "code": "YMBT",
  "country": "AU",
  "elevation": 334,
  "latlng": [
   -36.72895,
   147.1671
  ],
  "name": "Mount Beauty",
  "time_info": {
   "dawn": "05h31",
   "noon": "12h55",
   "sunrise": "05h59",
   "sunset": "19h53",
   "twilight": "20h21",
   "tz_name": "Australia/Sydney",
   "tz_offset": "AEDT+1100"
  }
 },
 "call_tsp": 1794263424,
 "code": "YMBT",
 "date": "2026-11-09",
 "devices": [
  {
   "address": "DDDC98",
   "aircraft": "ASK-21 MI",
   "aircraft_type": 1,
   "competition": "GVS",
   "db_org": "OGN",
   "device_type": "F",
   "identified": true,
   "registration": "GVS",
   "tracked": true
  },
  {
   "address": "D00BD9",
   "aircraft": "Libelle Club",
   "aircraft_type": 1,
   "competition": "GJF",
   "db_org": "OGN",
   "device_type": "F",
   "identified": true,
   "registration": "VH-GJF",
   "tracked": true
  },
  {
   "address": "7C2154",
   "aircraft": "ASK-21",
   "aircraft_type": 1,
   "competition": "VA",
   "db_org": "OGN",
   "device_type": "F",
   "identified": true,
   "registration": "VH-GVA",
   "tracked": true
  },
  {
   "address": "7C201A",
   "aircraft": "Hornet",
   "aircraft_type": 1,
   "competition": "MK",
   "db_org": "OGN",
   "device_type": "F",
   "identified": true,
   "registration": "VH-GMK",
   "tracked": true
  }
 ],
 "flights": [
  {
   "device": 0,
   "duration": 15740,
   "max_alt": 1913,
   "max_height": 1579,
   "start": "12h26",
   "start_delta": 654,
   "start_q": 32,
   "start_tsp": 1794187611,
   "stop": "16h49",
   "stop_q": 33,
   "stop_tsp": 1794203351,
   "tow": null,
   "towing": false,
   "warn": false
  },
  {
   "device": 1,
   "duration": 18015,
   "max_alt": 2713,
   "max_height": 2379,
   "start": "12h40",
   "start_q": 33,
   "start_tsp": 1794188449,
   "stop": "17h41",
   "stop_q": 33,
   "stop_tsp": 1794206464,
   "tow": null,
   "towing": false,
   "warn": false
  },
  {
   "device": 2,
   "duration": 4611,
   "max_alt": 1612,
   "max_height": 1278,
   "start": "13h02",
   "start_q": 33,
   "start_tsp": 1794189739,
   "stop": "14h19",
   "stop_q": 15,
   "stop_tsp": 1794194350,
   "tow": null,
   "towing": false,
   "warn": false
  },
  {
   "device": 3,
   "duration": 11525,
   "max_alt": 2699,
   "max_height": 2365,
   "start": "13h25",
   "start_delta": 1,
   "start_q": 12,
   "start_tsp": 1794191114,
   "stop": "16h37",
   "stop_q": 15,
   "stop_tsp": 1794202639,
   "tow": null,
   "towing": false,
   "warn": false
  }
 ],
 "rnames": [
  "YMBT"
 ]
}
```
