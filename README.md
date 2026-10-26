# Plant Placement Helper

An Android app and Kotlin/Ktor backend for photo-based plant suggestions. Choose a room photo to get plant ideas suited to the visible space, or identify a plant you already own and get a suggested placement. Results include a marked area on the room photo. Photo-based light guidance is an estimate, not a guarantee of daily sunlight.

## Project layout

- `app/` — Android app built with Kotlin and Jetpack Compose.
- `backend/` — standalone Ktor service; calls Gemini using a server-side API key.

## Build and test

From the repository root on Windows:

```powershell
.\gradlew.bat :app:assembleDebug :backend:test --console=plain
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Run the backend locally

1. Copy `backend/.env.example` to `backend/.env` and set `GEMINI_API_KEY` locally.
2. Run `.\gradlew.bat :backend:run --console=plain` from the repository root.
3. Check `http://localhost:8080/health`.

Never commit `backend/.env`. For deployment, configure `GEMINI_API_KEY` as a private Railway service variable. The backend service uses the `backend/` directory as its Railway root so Railway detects `backend/Dockerfile`.

The Android build defaults to the deployed Railway HTTPS backend URL; set the Gradle property `plantApiUrl` to override it for another environment.
