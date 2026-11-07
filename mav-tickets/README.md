# MÁV+ tickets (optional module)

Everything for the MÁV+ tickets tab lives in this folder: login, passes, barcodes, offline cache,
the ticket screen, its strings and `ticket_anim.gif`, and its tests.

## Plug in / remove

- **On:** put this folder at the repo root as `mav-tickets/`. `settings.gradle.kts` includes it
  automatically, and the app shows the **Jegyek / Tickets** tab.
- **Off:** delete or move the folder. The app compiles `app/src/ticketsStub/` instead and the tab
  is hidden. Nothing else needs to change.
- **Off for one build** with the folder present: `./gradlew assembleDebug -PnoTickets`.

## Keep it out of GitHub but local

```sh
echo "/mav-tickets/" >> .gitignore
git rm -r --cached mav-tickets
git commit -m "Keep MÁV tickets module local"
```

The folder stays on disk and keeps building locally; clones without it build the app without
the tab. (This only stops tracking it from now on; it stays in earlier commits.)

## Contract with the app

The app touches this module only through `hu.app.bmenetrend.plugins.TicketsPlugin`
(`src/main/java/hu/app/bmenetrend/plugins/TicketsPlugin.kt`). The stub in
`app/src/ticketsStub/java/hu/app/bmenetrend/plugins/TicketsPlugin.kt` must keep the same public
signatures. The tab label `tickets_nav` stays in the app's `ui_strings.xml`.
