# Island+ (ohne Root)

Zwei Dinge:
1. **Eigene Insel** (Overlay) mit großem Albumcover, aus dem Cover eingefärbt, animiert.
2. **Live-Hintergrund** mit Glas-Uhr + Tiefeneffekt (Motiv vor der Uhr) für den Sperrbildschirm.

## APK bauen
[Release-APK herunterladen](https://github.com/vectorcrypton/Hyper-Island-rework/releases/latest/download/app-release.apk)

1. Android Studio (aktuell) installieren → *Open* → Ordner `HyperIslandPlus`.
2. Gradle-Sync abwarten (lädt Gradle 8.9 + Abhängigkeiten, Internet nötig).
3. Für eine Release-APK: im Terminal `gradle :app:assembleRelease` ausführen.
   Die signierte APK liegt unter `app/build/outputs/apk/release/app-release.apk` und kann
   an ein GitHub-Release angehängt werden.

Der Release-Build verwendet den Android-Debug-Schlüssel. Das ist für direkt installierbare
GitHub-Releases geeignet, aber nicht für den Play Store. Für spätere App-Updates muss derselbe
Schlüssel erhalten bleiben; ein anderer Schlüssel verhindert Updates über die installierte App.

## Einrichten
1. App öffnen → Benachrichtigungszugriff + „Über anderen Apps einblenden“ erlauben.
   Falls der Schalter ausgegraut ist: App-Info → ⋮ → „Eingeschränkte Einstellungen zulassen“.
2. „Insel starten“, Song abspielen.
3. Xiaomi: App-Info → Autostart AN, Akku „Keine Einschränkungen“, in den letzten Apps festpinnen.
4. Optional: System-Hyperinsel für Musik abschalten (Einstellungen → Benachrichtigungen → Hyperinsel/Fokus), sonst liegt sie unter meiner.
5. Glas-Uhr: Bild wählen → „Live-Hintergrund aktivieren“ → „nur Sperrbildschirm“.

## Anpassen
- Abstand der Insel von oben: `IslandService.show()` → `y`
- Größen/Farben/Animation: `IslandView.kt`
- Glas-Look (Blur, Tönung, Rand): `GlassClockWallpaperService.glass()`
