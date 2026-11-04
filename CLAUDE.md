# SoarLog

The specification is [docs/spec.md](docs/spec.md).

- This repository builds an Android glider flight logbook. The specification and its appendices are the source of truth. Read them fully before writing code.
- Work one build-order step at a time. At the end of each step, list what was built, what was tested, and any deviation from the specification, then wait for the pilot's go-ahead before starting the next step.
- Do not build anything listed as out of scope, and do not add fields, screens or settings that the specification does not mention. Ask first. The LXNAV flight download is built but not yet tried on the logger; its "Not yet verified" list in the spec is what remains.
- The appendices hold proposed defaults. Follow them, but ask before changing a default, and ask for any value the specification leaves open. The app name is SoarLog, the package ID is `io.github.jgfyyvzss.soarlog`, and the minimum Android version is API 26.
- Check library versions, Android APIs and the IGC record layouts against official documentation instead of relying on memory. Prefer stable releases.
- Keep the `core` package free of `android.*` imports, and keep all WeGlide code behind one interface with a fake implementation.
- Put every piece of text the pilot sees in the `res/values/strings_*.xml` files, never as a literal in Kotlin. Code in `core` returns a `Msg` (a `MsgKey` with arguments), and each new key needs a `msg_` string and a line in `MsgKey.res()`. Keep CSV columns, JSON keys and stored values language-neutral.
- Write the tests listed in Appendix F as part of each step, and run them before reporting a step as done.
- Never log, export or commit the WeGlide ID, the date of birth or any other credential. The logbook is not an archive of IGC files: a received file is kept only until it is 7 days old, more than 20 are kept, or the pilot removes it.
- Do not make real WeGlide requests from automated tests. A manual upload with the pilot's own account happens only when the pilot asks for it.
- Use the files in `testdata` as the test fixtures. If a fixture is missing, ask the pilot rather than inventing data.
- The repository is public. Never commit personal data: real names other than the pilot's own, licence or membership numbers, or un-anonymised logbook exports.
