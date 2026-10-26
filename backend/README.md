# Plant Placement Backend

Ktor service that receives room-photo recommendation requests and calls Gemini. The Gemini API key belongs only in the server environment; never commit a real `.env` file or put the key in the Android app.

## Local setup

1. Copy `.env.example` to `.env` and set `GEMINI_API_KEY` to a valid key.
2. From this directory, run `./gradlew run` on macOS/Linux or `gradlew.bat run` on Windows.
3. Check `http://localhost:8080/health`.

The service reads `GEMINI_API_KEY` and optional `GEMINI_MODEL` from the process environment or local `.env` file. The default model is `gemini-2.5-flash`. The `PORT` environment variable controls the listening port; Railway supplies it automatically.

## Test and build

- Windows: `gradlew.bat test` and `gradlew.bat installDist`
- macOS/Linux: `./gradlew test` and `./gradlew installDist`

## Deploy to Railway from GitHub

Connect this backend-only repository to a Railway service. Railway should detect the repository-root `Dockerfile` and build the container automatically. In the Railway service Variables settings, add:

- `GEMINI_API_KEY` — your rotated Gemini API key
- `GEMINI_MODEL` — optional; defaults to `gemini-2.5-flash`

Railway provides `PORT`; do not hard-code a production port. After deployment, verify the public service URL at `/health`.

## Environment files

`.env.example` contains placeholders only. Keep local secrets in `.env`, which is excluded from Git and the Docker build context.