# SoarLog PDF template format

A guide for anyone writing a logbook PDF template for SoarLog. A template is one JSON file that sets the page, the margins, the columns and the totals. There is no editor in the app: write the file in any text editor, then import it on the phone.

The machine-readable definition is [pdf-template.schema.json](pdf-template.schema.json) (JSON Schema 2020-12). Editors such as VS Code check a template against it and complete field names as you type if the file starts with:

```json
"$schema": "https://raw.githubusercontent.com/Jgfyyvzss/SoarLog/main/docs/pdf-template.schema.json",
```

The app ignores the `$schema` key.

This describes template format version 1, as SoarLog 0.3.0 reads it.

## Installing a template

1. Copy the `.json` file to the phone, or to a cloud folder the phone can open.
2. In SoarLog: **Settings → Logbook PDF → Create PDF → Import template**, and pick the file.
3. The template appears in the list beside Simple list, Compact and GFA-style. Importing another template with the same `name` replaces the earlier one, and **Remove** deletes it.

If the file has a problem, the app says what it is and imports nothing (see [What the app refuses](#what-the-app-refuses)).

## A complete example

This is the built-in GAus-style template, which copies the layout of a GAus glider pilot's logbook spread:

```json
{
  "format": "soarlog-pdf-template",
  "version": 1,
  "name": "GAus-style",
  "page": { "size": "A4", "orientation": "landscape" },
  "margins_mm": { "top": 8, "bottom": 8, "left": 8, "right": 8 },
  "font_size": 8,
  "rows_per_page": 20,
  "header": { "title": "", "show_pilot": true },
  "labels": {
    "brought_forward": "Totals brought forward",
    "page": "Total for page",
    "carried_forward": "Grand totals to date"
  },
  "columns": [
    { "field": "log_no", "width": 0.9, "align": "end", "title": ["Serial", "No. of", "Flight"] },
    { "field": "date", "width": 1.1, "title": ["Date"] },
    { "field": "glider", "width": 1.8, "title": ["Glider", "Type/Rego"] },
    { "field": "place", "width": 1.4, "title": ["Place"] },
    { "field": "launch_type", "width": 1.0, "title": ["Launch", "Type"] },
    { "field": "time", "width": 1.4, "title": ["Dual/Inst."], "roles": ["STUDENT"], "instruction": "when_not_pic", "split_hm": true },
    { "field": "time", "width": 1.4, "title": ["In Com."], "roles": ["PIC", "MUTUAL"], "instruction": "when_pic", "split_hm": true },
    { "field": "gap", "width": 1.1 },
    { "field": "notes", "width": 6.8, "title": ["Remarks", "Exercise / Task / Turnpoints / Landing point / Km"] },
    { "field": "witness", "width": 2.2, "title": ["Trainers", "Signature"] }
  ]
}
```

The smallest template the app accepts names itself and lists at least one column; everything else has a default:

```json
{
  "format": "soarlog-pdf-template",
  "name": "Minimal",
  "columns": [ { "field": "date" }, { "field": "duration" } ]
}
```

## Top level

| Key | Required | Type | Default | Rules |
| --- | --- | --- | --- | --- |
| `format` | yes | text | — | Must be `soarlog-pdf-template`. |
| `version` | no | whole number | `1` | Must not be above 1. A file for a newer version is refused, with a message to update the app. |
| `name` | **yes** | text | — | Not blank. Shown in the app's template list; an import with the same name replaces the earlier template. |
| `page` | no | object | A4 portrait | See [Page](#page). |
| `margins_mm` | no | object | 12 mm each side | See [Margins](#margins). |
| `font_size` | no | number (points) | `8` | 4 to 20. One size for the whole page; headings and totals are bold, and the title is 1.4 times larger. |
| `rows_per_page` | no | whole number | `30` | 1 to 200. Flight rows on each page, not counting the three totals rows. |
| `header` | no | object | the app's title, pilot shown | See [Header](#header). |
| `labels` | no | object | the app's own words | See [Totals labels](#totals-labels). |
| `columns` | **yes** | list of objects | — | At least one. See [Columns](#columns). |

Keys the app does not know are ignored, so a template can carry notes such as `"_comment"`. The schema is stricter and flags unknown keys, to catch misspellings; it allows keys that start with `_` and `$schema`.

## Page

| Key | Required | Type | Default | Rules |
| --- | --- | --- | --- | --- |
| `size` | no | text | `A4` | `A4` (210 × 297 mm), `A5` (148 × 210 mm), `LETTER` (215.9 × 279.4 mm) or `CUSTOM`. Upper or lower case. |
| `orientation` | no | text | `portrait` | `portrait` or `landscape`. Upper or lower case. |
| `width_mm` | only with `CUSTOM` | number | — | 50 to 1000. |
| `height_mm` | only with `CUSTOM` | number | — | 50 to 1000. |

Orientation decides which side is longer, so a `CUSTOM` page can give its sides in either order. A spread of two A5 pages is a `CUSTOM` page of 296 × 210 mm in landscape, or A4 landscape (297 × 210 mm) as GFA-style does.

## Margins

`margins_mm` has `top`, `bottom`, `left` and `right`, each in millimetres and each 12 by default. Leave out any you do not change. Left and right together must be less than the page width, and top and bottom less than its height.

## Header

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `title` | text | the app's "Pilot logbook", in the pilot's language | Printed at the top left of each flight page. An empty string `""` prints no title. |
| `show_pilot` | true or false | `true` | Prints the pilot's name and identifying numbers from the app's profile at the top right. |

## Totals labels

Every flight page has three totals rows: brought forward at the top, then the page total and carried forward at the foot. Each row's label is followed by its number of flights, for example "Total for page · 20 flights".

| Key | Default |
| --- | --- |
| `brought_forward` | "Brought forward", in the pilot's language |
| `page` | "Page total" |
| `carried_forward` | "Carried forward" |

Leave a key out to use the app's words, which follow the phone's language. A label you write is printed exactly as written.

## Columns

`columns` lists the columns from left to right. Each is an object:

| Key | Required | Type | Default | Meaning |
| --- | --- | --- | --- | --- |
| `field` | **yes** | text | — | What the column prints; see [Fields](#fields). |
| `width` | no | number | `1` | Relative width, above 0. The columns share the space between the margins in proportion: widths 1, 1 and 2 give a quarter, a quarter and a half. |
| `title` | no | list of text | the app's heading for the field | Heading lines, top to bottom, for example `["Launch", "Type"]`. The heading area is as tall as the column with the most lines. `[]` prints no heading. |
| `align` | no | text | `start` | `start`, `center` or `end`. Any other value is treated as `start`. Use `end` for numbers. Headings are always centred. |
| `roles` | for `time` | list of text | — | The roles whose time goes in a `time` column; see [Time columns](#time-columns). |
| `instruction` | no | text | `never` | For `time` columns: when Instructor and Coach time goes here too. |
| `split_hm` | no | true or false | `false` | For `duration` and `time` columns: separate Hrs and Min sub-columns, as in a printed book. Takes effect only while the app shows hours as H:MM; with decimal hours the column prints one figure. |
| `total` | no | true or false | `true` for `duration` and `time`, otherwise `false` | Whether the three totals rows add the column up. Only `duration` and `time` columns have minutes to add, so `true` on another column prints nothing. Set `false` to leave a time column untotalled. |

### Fields

| `field` | Prints | Notes |
| --- | --- | --- |
| `log_no` | The flight's log number | |
| `date` | The flight date | In the phone's short date format. |
| `glider` | Registration and type together, such as `FQM Hornet` | |
| `registration` | The registration only | |
| `glider_type` | The type only | |
| `airfield` | The departure airfield's name | |
| `place` | The airfield, then the club when there is one, such as `YMBT MBGC` | |
| `club` | The club, or nothing | |
| `launch_type` | The launch type | Seeded types (Aero, Winch, Self, Auto) appear in the pilot's language until renamed. |
| `role` | PIC, Student, Instructor, Coach or Mutual | In the pilot's language. |
| `pic` | The pilot in command's name | |
| `duration` | Every flight's duration | Totalled. |
| `time` | The duration only for the roles in `roles` | Totalled. See [Time columns](#time-columns). |
| `launch_time` | Launch time, HH:MM | The column is left out entirely unless the app's **Record launch and landing times** setting is on. |
| `landing_time` | Landing time, HH:MM | As for `launch_time`. |
| `landing_place` | The landing airfield or place, or nothing when the flight ended where it began | Left out entirely unless **Record landing place** is on. |
| `notes` | The flight's notes, on one line | Line breaks become spaces. |
| `witness` | The witness's name and number | Only for a flight whose witness seal still matches, while **Witness sign-off** is on; otherwise empty, so it doubles as a hand-signature column. |
| `blank` | Nothing | A ruled, empty column, such as for a signature. |
| `gap` | Nothing, with no lines | Unruled space, such as the fold between the two pages of a spread. It has no heading. The grid is drawn as one box per run of columns between gaps. |

A `field` value the app does not know makes the file unreadable, so check the spelling against this list.

When the app leaves a `launch_time`, `landing_time` or `landing_place` column out, the remaining columns share its width.

### Time columns

A paper logbook usually splits time into columns by kind, such as dual and in command. A `time` column takes the duration of flights in the roles it lists:

- **`roles`:** any of `PIC`, `STUDENT`, `INSTRUCTOR`, `COACH` and `MUTUAL`, in capitals. At least one is required.
- **`instruction`:** decides whether Instructor and Coach flights also go here, following the app's setting that counts instruction as PIC time:

| `instruction` | Instructor and Coach time goes here |
| --- | --- |
| `never` (default) | Only if INSTRUCTOR or COACH is in `roles` |
| `always` | Always |
| `when_pic` | When the setting counts instruction as PIC |
| `when_not_pic` | When that setting is off |

GFA-style uses this pair, so instruction lands in exactly one column whichever way the setting is:

```json
{ "field": "time", "title": ["Dual/Inst."], "roles": ["STUDENT"], "instruction": "when_not_pic" },
{ "field": "time", "title": ["In Com."], "roles": ["PIC", "MUTUAL"], "instruction": "when_pic" }
```

A flight counts in every time column that takes its role, as on the app's Totals screen. Columns that share a role therefore count that flight twice, which suits a "total time" column beside per-role columns:

```json
{ "field": "duration", "title": ["Total"] },
{ "field": "time", "title": ["PIC"], "roles": ["PIC"], "instruction": "when_pic" },
{ "field": "time", "title": ["Dual"], "roles": ["STUDENT"] }
```

## How the app lays out a page

These rules are fixed. Knowing them helps a template fit.

- **Font:** Roboto, bundled with the app, so every language prints the same way. It cannot be changed.
- **Rows:** a row is the space left after the header and footer, divided by `rows_per_page` plus 3 (for the totals rows), and never taller than 2.4 times the font size. Too many rows for the page gives cramped rows rather than an error. Try about 20 rows on a landscape A4 page at 8 points.
- **Text:** one line per cell. Text that does not fit is cut short with "…".
- **Totals label:** the label sits across the columns to the left of the first totalled column, or, when a `gap` comes before that column, up to the last such gap. Put at least one column before the first time column, or the label is not printed.
- **Hrs and Min:** with `split_hm`, the hours take 60 % of the column and the minutes 40 %.
- **Footer:** the date the PDF was made and "Page N of M". When the pilot has a filter set, the filter and a note that the totals cover only those flights come first.
- **Opening balances:** with no filter set, page 1 brings forward the opening balances of the roles each time column takes. With a filter, totals start from zero.
- **Endorsements:** after the last flight page, the app prints the pilot's endorsements on further pages, four to a page. They use the template's page size, margins and font size, but not its columns.

## What the app refuses

Nothing is imported when a template is refused. The message says why:

| Message | Cause |
| --- | --- |
| "This is not a SoarLog PDF template" | The file is not valid JSON; `format` is wrong; `name` or `columns` is missing; or a `field`, `role` or `instruction` value is not one listed here. |
| "This template needs a newer version of SoarLog" | `version` is above 1. |
| "The template has a problem with name" | `name` is blank. |
| "… with columns" | `columns` is empty. |
| "… with rows_per_page" | Not 1 to 200. |
| "… with font_size" | Not 4 to 20. |
| "… with page" | Unknown `size` or `orientation`; `CUSTOM` without both sizes; or a side under 50 or over 1000 mm. |
| "… with margins_mm" | The margins leave no room on the page. |
| "… with width" | A column's `width` is 0 or less. |
| "… with roles" | A `time` column has no `roles`. |

The app checks one thing at a time, so fix the problem it names and import again. Checking the file against the schema first catches most of these at once.

## Tips

- Start from a built-in template; their files are in [app/src/main/assets/pdf-templates](../app/src/main/assets/pdf-templates).
- Measure the paper book: set `page` and `margins_mm` to match it, then make each `width` the column's width in millimetres. Only the proportions matter, so real measurements work as they are.
- Give `title` lines in the language of the book you are copying. Leave them out to have the app's headings follow the phone's language.
- Leave `labels` out for the same reason, unless the book's wording matters.
- Print a page on plain paper and hold it over the real book to check the fit.
