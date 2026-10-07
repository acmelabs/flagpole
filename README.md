# flagpole

[![CI](https://github.com/acmelabs/flagpole/actions/workflows/ci.yml/badge.svg)](https://github.com/acmelabs/flagpole/actions/workflows/ci.yml)

A lightweight, read-only feature flag service. Flags live in a single YAML file, are changed through pull
requests, and are served over a small JSON API plus a read-only web UI.

- Java 21, Micronaut 4, plain blocking code, builds as a GraalVM native image.
- No database: one YAML file per instance, path given by `FLAGS_FILE`.
- Hot reload: the file is checked every 5s and swapped in atomically, but only if it is valid.

## Running locally

```bash
# Run the tests (Spock, on the JVM)
./gradlew test

# Run against the sample file
FLAGS_FILE=$PWD/flags.yaml ./gradlew run
```

Then open http://localhost:8080 (UI), http://localhost:8080/swagger-ui (API docs),
http://localhost:8080/api/flags (API), or http://localhost:8080/health.

Building a native binary locally needs GraalVM 21 with `native-image` on your `PATH`:

```bash
./gradlew nativeCompile
FLAGS_FILE=$PWD/flags.yaml build/native/nativeCompile/flagpole
```

### Configuration

| Env var                  | Property                | Default  | Meaning                                  |
|--------------------------|-------------------------|----------|------------------------------------------|
| `FLAGS_FILE`             | `flags.file`            | required | Path to the flags YAML file              |
| `FLAGS_RELOAD_INTERVAL`  | `flags.reload-interval` | `5s`     | How often the file is checked for changes |
| `MICRONAUT_SERVER_PORT`  | `micronaut.server.port` | `8080`   | HTTP port                                |
| `LOG_FORMAT`             |                         | `text`   | `text`, or `json` for one JSON object per line (set in the Docker images); anything else fails startup |
| `ACCESS_LOG_ENABLED`     | `micronaut.server.netty.access-logger.enabled` | `true` | Log every request, except `/health*`, `/static`, `/swagger-ui` |
| `CORS_ALLOWED_ORIGINS`   | `micronaut.server.cors.configurations.api.allowed-origins` | none (CORS off) | Comma-separated exact origins allowed to call the API from a browser |

## Flag file format

```yaml
flags:
  new-checkout:
    enabled: true          # required
    rollout: 25            # optional, integer 0-100, default 100
    allow: [user_42]       # optional, user IDs that always get the flag (if enabled: true)
    description: "New checkout flow"   # optional
  dark-mode:
    enabled: false
```

Validation is strict, and **all** problems are reported at once:

- Flag names must match `^[a-z0-9][a-z0-9_.-]*$`.
- `enabled` is required and must be `true` or `false`. YAML 1.1 spellings like `yes`/`no`/`on`/`off` are not
  booleans here; they are read as plain strings (so `allow: [no]` or a flag named `on` work as expected).
- `rollout` must be an integer from 0 to 100.
- `allow` must be a list of non-blank strings. Quote numeric IDs (`allow: ["42"]`) so YAML doesn't read them as numbers.
- Unknown keys are rejected, so typos like `rolout:` fail instead of being silently ignored.
- Duplicate flag names are rejected.
- `flags: {}` (no flags) is valid. A missing top-level `flags:` key is not.

## Evaluation rules

Evaluated in order. The first matching rule wins (an unknown flag is not evaluated: the API returns `404`):

1. `enabled: false` → `false`.
2. `userId` is in `allow` → `true`.
3. `rollout: 100` → `true`. No `userId` (missing or blank) and `rollout < 100` → `false`.
4. Otherwise, `bucket = uint32(first 4 bytes of SHA-256("<flagName>:<userId>")) mod 100`, and the flag is
   enabled if `bucket < rollout`.

Bucketing is deterministic and language-independent, so any client can reproduce it, and results stay
stable across restarts and instances. Raising the rollout only adds users; nobody who already has the flag
loses it. Each flag hashes its own name, so different flags at the same percentage reach different users.

## Reloading

- The file is loaded and validated at startup. If it is missing or invalid, the process exits with an error.
- Every `FLAGS_RELOAD_INTERVAL` the file is read and its SHA-256 compared to the current version.
  The check uses content, not mtime, so Kubernetes ConfigMap updates (an atomic `..data` symlink swap) are
  always detected.
- A changed file is parsed and validated in full first. Only then is it swapped in atomically, as one
  immutable snapshot.
- If a reload fails (invalid YAML, failed validation, unreadable file), an error is logged once per distinct
  problem (not on every check) and **the last good config keeps being served**. The service never falls back to empty flags.
  `/health` stays `UP` and shows `lastReloadError`.

## HTTP API

Interactive docs are at **`/swagger-ui`**, and the OpenAPI 3 spec is at **`/swagger/flagpole.yml`**. Use the
spec to generate clients or import into Postman. Both are generated at compile time from the controllers by
micronaut-openapi (settings in `openapi.properties`). Swagger UI's assets ship inside the app, not from a
CDN. The HTML UI routes are excluded from the spec.

The config version is the SHA-256 of the flags file.

### `GET /api/flags`

All flag definitions, plus the version. The response carries `ETag: "<version>"`. Send it back as
`If-None-Match` to get `304 Not Modified` while nothing has changed. That makes polling cheap.

```json
{
  "version": "ff53df08…",
  "loadedAt": "2026-10-06T11:07:45.081Z",
  "flags": [
    {"name": "dark-mode", "enabled": false, "rollout": 100, "allow": []},
    {"name": "new-checkout", "enabled": true, "rollout": 25, "allow": ["user_42"], "description": "New checkout flow"}
  ]
}
```

### `GET /api/flags/{name}`

One flag definition. Returns `404` if the flag is unknown.

### `GET /api/flags/{name}/evaluate?userId=...`

```json
{"flag": "new-checkout", "enabled": true}
```

Returns `404` if the flag is unknown. `userId` is optional.

### `GET /api/evaluate?userId=...`

Every flag evaluated for one user, in a single call that clients can cache. It supports
`ETag`/`If-None-Match` like `/api/flags`, but the ETag also includes a hash of the `userId`
(`"<version>-<hash>"`), so a cached result for one user never revalidates as another's. A missing or blank
`userId` is anonymous: the ETag is just the version and `userId` is `null` in the response.

```json
{"version": "ff53df08…", "userId": "user_42", "flags": {"dark-mode": false, "new-checkout": true}}
```

### `GET /health`

Standard Micronaut health; `/health/liveness` and `/health/readiness` are the Kubernetes probe variants. The `flags` indicator includes `version`, `lastReloadAt` (when the served version was loaded),
`lastSuccessfulCheckAt` (when the file was last read and found valid, changed or not), `flagCount`, `file`,
and, if the latest reload failed, `lastReloadError` and `lastReloadErrorAt`.

### CORS

Off by default: only browser apps on another origin that call the API directly need it. Set
`CORS_ALLOWED_ORIGINS=https://app.example.com,https://admin.example.com` to allow `GET`/`HEAD` with
`If-None-Match` from those exact origins, with `ETag` exposed and no credentials. Other origins get no CORS
headers, so browsers block them. For a pattern, set
`micronaut.server.cors.configurations.api.allowed-origins-regex` instead.

## UI

`/` shows a read-only table of the flags (name, enabled, rollout, allow-list size, description), plus the
config version and last reload time. The "Test a user" box evaluates every flag for a userId and shows why
each result came out as it did. It updates in place via htmx. htmx is served from `/static/htmx.min.js`, not
from a CDN.

There is no editing in the UI. Change flags with a pull request to the flags file.

## Docker

Prebuilt native images for `linux/amd64` and `linux/arm64` are published to the GitHub Container Registry:

```bash
docker run -p 8080:8080 -v "$PWD/flags.yaml:/config/flags.yaml:ro" ghcr.io/acmelabs/flagpole:latest
```

| Tag             | Built from                         |
|-----------------|------------------------------------|
| `latest`        | the newest `v*` release tag        |
| `1.2.3`, `1.2`  | release tag `v1.2.3`               |
| `main`          | the latest commit on `main`        |
| `sha-<commit>`  | that commit (on `main` or a tag)   |

To build locally:

```bash
docker build -t flagpole .                         # native image (GraalVM, distroless runtime)
docker build -f Dockerfile.jvm -t flagpole:jvm .   # JVM fallback

docker run -p 8080:8080 -v "$PWD/flags.yaml:/config/flags.yaml:ro" flagpole
```

Both images default `FLAGS_FILE` to `/config/flags.yaml` and `LOG_FORMAT` to `json`.

## CI and releases

`.github/workflows/ci.yml` runs on every push and pull request:

1. **Build and test**: `./gradlew build` on JDK 21. The test report is uploaded as an artifact if it fails.
2. **Docker image**: builds the native image for `linux/amd64` and `linux/arm64` in parallel, each on a runner
   of that architecture (native-image can't cross-compile). Pull requests only build them.
3. **Publish** (pushes to `main` and `v*` tags only): combines both into one multi-arch tag on
   `ghcr.io/acmelabs/flagpole`, so `docker pull` picks the right architecture automatically.

To release, bump `version` in `build.gradle.kts`, merge, then tag that commit:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

## Deploying one instance per environment

The service has no notion of environments. Run one instance per environment (dev, staging, prod), each with
its own flags file, e.g. a repo layout like:

```
flags/
  dev.yaml
  staging.yaml
  prod.yaml
```

In Kubernetes, ship each file as a ConfigMap and mount it as a **directory**. Don't use `subPath`: subPath
mounts never receive ConfigMap updates.

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: flagpole-flags
  namespace: prod
data:
  flags.yaml: |
    flags:
      new-checkout:
        enabled: true
        rollout: 25
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: flagpole
  namespace: prod
spec:
  replicas: 2
  selector:
    matchLabels: {app: flagpole}
  template:
    metadata:
      labels: {app: flagpole}
    spec:
      containers:
        - name: flagpole
          image: ghcr.io/acmelabs/flagpole:0.1.0
          env:
            - name: FLAGS_FILE
              value: /config/flags.yaml
          ports:
            - containerPort: 8080
          # Use the split probes, not /health: that aggregate includes checks like disk space, and failing
          # liveness on those would restart the pod. /health/liveness only checks for deadlocked threads.
          readinessProbe:
            httpGet: {path: /health/readiness, port: 8080}
          livenessProbe:
            httpGet: {path: /health/liveness, port: 8080}
          volumeMounts:
            - name: flags
              mountPath: /config
              readOnly: true
      volumes:
        - name: flags
          configMap:
            name: flagpole-flags
```

A merged PR updates the ConfigMap (e.g. via your GitOps tool, or
`kubectl create configmap flagpole-flags --from-file=flags.yaml=flags/prod.yaml -o yaml --dry-run=client | kubectl apply -f -`).
The kubelet syncs the mounted file, usually within about a minute, and flagpole picks it up on its next
check. Validate the file in CI before merging, so a broken file never reaches the cluster. Even if one
does, the running instances keep serving the last good config.

## Project layout

```
src/main/java/app/acmelabs/flagpole/
  config/FlagsConfig          flags.file, flags.reload-interval
  model/FlagDefinition        one validated flag (immutable)
  model/FlagSnapshot          all flags + version + load time (immutable)
  loader/FlagFileParser       YAML -> validated snapshot
  loader/FlagStore            AtomicReference<FlagSnapshot>, fail-fast load, reload
  loader/FlagReloadJob        @Scheduled reload
  eval/FlagEvaluator          evaluation rules + bucketing
  util/Sha256                 hashing for version, ETags and bucketing
  api/FlagController          JSON API with ETag support (+ OpenAPI annotations)
  health/FlagsHealthIndicator version / last reload in /health
  ui/UiController             Thymeleaf page + htmx fragment
src/main/resources/views      Thymeleaf templates
src/main/resources/logback*.xml  logging; LOG_FORMAT picks logback-text.xml or logback-json.xml
src/main/resources/public     htmx.min.js, app.css
src/test/groovy               Spock specs
openapi.properties            OpenAPI / Swagger UI generation settings
```

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

`src/main/resources/public/htmx.min.js` is [htmx](https://htmx.org), bundled under its own
license (Zero-Clause BSD).
