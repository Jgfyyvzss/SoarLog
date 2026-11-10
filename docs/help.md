# SoarLog help

A personal glider logbook. Everything stays on the phone; backups go to a folder you choose.

## First run
- **Start fresh:** an empty logbook.
- **Restore from backup:** load a backup (.zip) file, including settings and pilot profile.

## Home
- **New flight:** opens a blank flight form.
- **From IGC:** pick an IGC file to pre-fill a flight (also opens when another app shares an IGC file).
- **From logger:** download a flight from an LXNAV logger over Bluetooth.
- **From OGN:** fetch a day's glider flights at an airfield from OGN Flightbook.
- **Upload to WeGlide:** send an IGC file to WeGlide.
- **Aircraft logbook:** the calculator for a glider's own logbook.
- **Totals card:** PIC and Combined, unfiltered, opening balances included.
- **By aircraft type:** hours and flights for your three most-flown types.
- **Last backed up:** tap when stale to back up.
- **Bottom bar:** Home, Flights, Totals, Airfields, Gliders. 
- **Gear icon:** Settings.

## Flights
- Newest first, grouped by day; tap a flight to edit it; **+** for a new flight.
- **Filter:** glider (multi), airfield (multi), club (multi), date range, role, log number range, launch time range (Times setting), Witness (Witness setting).
- **Chips:** show the active filter; tap one to remove it. The same filter applies to Totals and the PDF.
- **Day header buttons:** Aircraft logbook (calculator for that day), Witness (Witness setting).
- **Marks:** Witnessed, Changed, Cannot verify (Witness setting).

## Flight form
- **Log no.:** next free number pre-filled; duplicates are refused.
- **Date, Glider, Airfield, Club, Launch type:** dropdowns; **+ New glider / + New airfield** add one on the spot.
- **Defaults:** a new hand-entered flight starts with the default glider and default airfield; the airfield fills its club and launch type.
- **Role:** PIC, or Dual with Student, Instructor, Coach or Mutual.
- **Pilot in command:** your profile name for PIC, Instructor and Coach; another name for Student and Mutual.
- **Duration:** digits, last two are minutes (324 = 3:24), or H:MM.
- **Launch and landing times:** with the Times setting; fill the duration.
- **Landing place:** with the Place setting; Same as departure, another airfield, or Other place.
- **Notes.**
- **Warnings:** shown once; **Save anyway** to keep the flight.
- **Delete flight:** frees the log number if it is the highest.
- **From IGC:** launch and landing preview, nearby airfields, Create an airfield at the launch point.
- **Witness card** (Witness setting): who witnessed and when, the photo, the status; Witness again, Remove photo, Remove witness mark. Changing a signed field asks first.

## IGC import
- Lists each flight found in the file with its launch time and duration.
- **Import:** opens a pre-filled flight form for that flight.
- **Status:** whether the file is in the logbook and on WeGlide.
- **Upload to WeGlide:** send the same file on.
- **Choose another file / Done.**

## From logger (LXNAV)
- **Allow Bluetooth**, then **choose the paired logger**.
- **Flight list:** date, size and download time; **More** for older flights.
- **Tap a flight** to download it: progress, time left, **Cancel download**; an interrupted download continues.
- The file then opens in IGC import, or in WeGlide upload when started from there.

## From OGN
- **What is sent:** only the airfield code and the date, one request per search. A day already fetched is not asked for again until the app closes.
- **Airfield:** pick one; an airfield without a code asks for it (usually the ICAO code) and keeps it. **+ New airfield:** enter a code; an airfield already within range of that position is offered first, otherwise the airfield form opens with what OGN knows.
- **Date:** today by default. It is the airfield's local date, and times are local at the airfield.
- **Glider (optional):** shows only that glider's flights; the whole day is still fetched.
- **Flights:** most recent first, nothing ticked. Only aircraft tracked by OGN appear. Tow planes and other aircraft are hidden until the switch is on.
- **Marks:** Already in logbook (same glider and date, launch within 5 minutes, or duration within 2 minutes without times), No landing time, Time approximate (OGN was up to an hour unsure), Check time (over an hour unsure), Launched at, Landed at, Glider not in your list, Several gliders match, Unidentified aircraft, Likely duplicate (two trackers).
- **Review:** the ticked flights as they will appear in Flights, numbered from the next free log number in launch order. Tap one to open the flight form; Save returns it to the review. **Set for every flight:** launch type, role, club. A marked flight must be fixed first; a Check time flight is confirmed by opening and saving it.
- **Filled in:** role PIC with your profile name; launch type Aero when OGN saw the tow, otherwise the airfield's default; club from the airfield; times only with the Times setting, and then the duration is landing minus launch. A glider not in your list is created when you Create, marked Incomplete.
- **Notes:** an aerotow gets "AeroTow 640 m (2100 ft) AGL, OGN": the tow plane's highest point above the airfield, from its own tracker and GPS, so it is an estimate of the release height. With the Place setting off, a landing at another known airfield gets "Landed at" and its code. An outlanding away from an airfield is not detected; correct it by hand.
- **Create:** writes all the flights or none, then opens Flights. Flights dated before your newest flight are numbered after it, after a warning. **Undo last import** (Spreadsheets) removes them.

## Upload to WeGlide
- **Choose file**, or pick one of the **Received IGC files** (kept for 7 days; **Remove from phone**).
- **WeGlide aircraft:** search the list if the glider has no WeGlide type yet.
- **WeGlide account:** WeGlide ID and date of birth, stored encrypted; **Change**.
- **Upload to WeGlide:** shows the WeGlide flight details, or the error.
- **Upload another file.**

## Witness sign-off (Witness setting)
- **Flights to witness:** tick the flights; unwitnessed ones start ticked.
- **Witness name** (required), **number** (optional), or tap an **earlier witness**.
- **What is stored:** read by the witness first.
- **Take photo:** required; the phone's camera app.
- **Consent tick**, then **Save sign-off**.

## Totals
- Rows: PIC, Student, Instructor, Coach, Mutual, Combined; columns: hours and flights.
- No filter: opening balances included. Filter active: only matching flights.
- Your own time as a pilot, not aircraft time.

## Endorsements (below the Totals grid)
- Every endorsement, newest first; the filter does not apply. **Add** starts a new one.
- **Type** (any text, for example Winch launch) and **Date** (the date on the page).
- **Take photo** or **Choose image** of the logbook page; optional. Tap the picture to view it full screen and pinch to zoom.
- **Witness** (Witness setting): after the first save, a witness can sign the endorsement as for flights. Changing it later shows Changed.

## Airfields
- List with clubs; **+** to add; tap to edit.
- **Name, latitude, longitude** (needed for IGC matching), **elevation, match radius.**
- **Code** (optional): usually the ICAO code, such as YMBT; shown before the name in the list. Each code belongs to one airfield.
- **Clubs:** add an existing one or remove one.
- **Flight form defaults:** default club and launch type.
- **Default for new flights:** one airfield; **Default** badge in the list.
- **Archived:** hidden from the flight form. **Incomplete** badge: no coordinates yet.

## Gliders
- List with type; badges: **Default**, **Incomplete**, **No WeGlide type**, **Archived**; **+** to add.
- **Registration, Type** (as the logbook shows it).
- **Default for new flights.**
- **Archived:** hidden from the flight form.
- **WeGlide type:** search and set; replaces the type text.
- **Logbook total:** the last total written in the glider's own logbook; **Edit total**, **Calculator**.

## Aircraft logbook calculator
- Pick the day (and the glider if several flew).
- **Flights on the day:** untick any to leave out; **Other pilots** row for flights not in your log.
- **Last entry:** the stored total, editable; **Total:** the new figure.
- **Save new total:** stores it with the day; warns if the day may already be included.

## Settings
- **Pilot profile:** name (default pilot in command) and any numbers (licence, membership, ARN); **Save profile**.
- **Flight form:** Record landing place, Record launch and landing times, Witness sign-off.
- **Totals:** hours display (H:MM or decimal), Instructor and Coach count as PIC, **Opening balances**.
- **Backup:** see below.
- **Spreadsheets (CSV):** see below.
- **Logbook PDF:** see below.
- **WeGlide:** aircraft list (**Update**), saved account (**Remove**).
- **Lists:** Clubs, Launch types.

## Opening balances
- Hours and flights per role from your paper logbook, with the date of the last paper entry.
- Added to Totals when no filter is set.

## Backup
- **Choose folder:** where backups go; can be cloud-synced.
- **Back up automatically after** N changes or N days (0 turns a trigger off); the last 5 are kept. Move older ones out of the folder to keep them.
- **Include images:** witness photos and endorsement pictures.
- **Back up now.**
- **Restore:** into an empty logbook only; replaces settings and profile. An old .json backup needs SoarLog 0.2.0: restore it there, then update.
- **Merge:** combines a backup with this logbook; newer records win; settings stay.

## Spreadsheets (CSV)
- **Export to a folder:** flights, gliders and airfields as three files.
- **Import flights / gliders / airfields:** the whole file is checked first; nothing is imported if a row fails.
- **Unmatched names:** create a stub to complete later, or pick an existing record.
- **Defaults:** launch type and pilot in command for rows without one; numbering when there is no log_no.
- **Rows already in the logbook:** **Skip existing** or **Overwrite** (blank cells clear, missing columns keep; a backup is written first).
- **Undo last import:** removes that import's new flights and unused stubs.

## Logbook PDF
- Prints the flights matching the Flights filter, oldest first, with page totals.
- **Template:** Simple list, Compact, GFA-style; **Import template**, **Remove**.
- **Save to file** or **Share.**

## Clubs and Launch types
- **Clubs:** add and rename.
- **Launch types:** Aero, Winch, Self and Auto to start; add, rename, archive.
