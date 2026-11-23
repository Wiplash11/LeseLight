# LeseLight für Android

Diese Android-App packt die Web-App `web/leselicht.html` in eine eigene App und fügt Handy-Funktionen hinzu.

## Was die App kann
- Vorlesen mit den Stimmen deines Handys (Android-Sprachausgabe). Am natürlichsten klingen Stimmen mit „Premium“ im Namen.
- Kamera und Dateien (PDF, Word, TXT, Bilder) öffnen, Texte als .txt in Downloads/Leselicht speichern.
- Texte aus anderen Apps über „Teilen“ an Leselicht schicken.
- Texterkennung aus Fotos und die KI-Regie laufen über die Claude-API mit deinem eigenen API-Schlüssel (in der App unter Einstellungen eintragen).

## APK bauen

**Über GitHub:** Den Ordner in ein GitHub-Repository hochladen. Der Workflow `.github/workflows/apk.yml` baut die APK bei jedem Push auf `main` und hängt sie als Release an (Reiter „Releases“).

**Mit Android Studio:** Ordner öffnen, warten bis Gradle fertig ist, dann *Build › Build APK(s)*.

**Mit der Kommandozeile:** `gradle assembleRelease` (Gradle 8.9 oder neuer, JDK 17, Android SDK). Die APK liegt dann unter `app/build/outputs/apk/release/app-release.apk`.

## Web-Teil aktualisieren
Nach Änderungen an `web/leselicht.html` einmal `python3 web/build_index.py` ausführen. Das kopiert die Web-App nach `app/src/main/assets/www/`.
