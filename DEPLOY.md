# allure-jira-bridge — deployment notes for DevOps

Stateless HTTP reverse proxy in front of Jira (https://jira.asgardia.uz). Allure TestOps (cloud,
smartcast.testops.cloud) calls it instead of Jira; it forwards everything to Jira and adds two fields when
an issue is created. Details: README.md.

## What is needed

| Item | Value |
|---|---|
| Runtime | Java 17 (or the Docker image from `Dockerfile`) |
| Port | `8080` inside the container (`PORT` env to change) |
| Public URL | **HTTPS, reachable from the internet** — Allure is a cloud service. Example: `https://allure-jira.<domain>` |
| TLS | Terminate on the ingress / reverse proxy (nginx, traefik…). The app itself speaks plain HTTP |
| Outbound | HTTPS to `jira.asgardia.uz:443` |
| Health check | `GET /actuator/health` → `200 {"status":"UP"}` |
| Database / volumes | None |
| Secrets | `PATH_SECRET` (required, see below). Jira credentials come from Allure in each request and are passed through, not stored |
| Resources | 1 instance, 512 MB RAM (only "create issue" bodies, max 1 MB, are held in memory; attachments are streamed), 0.25 CPU |

## Build and run

```bash
docker build -t allure-jira-bridge:0.1.0 .
docker run -d --name allure-jira-bridge -p 8080:8080 -e PATH_SECRET=<secret> --restart unless-stopped allure-jira-bridge:0.1.0
```

Without Docker: `mvn package && java -jar target/allure-jira-bridge-0.1.0.jar`.

## Environment variables (all but `PATH_SECRET` optional, defaults are production values)

| Variable | Default |
|---|---|
| `PATH_SECRET` | **required**, no default; the app does not start without it. At least 32 chars of `[A-Za-z0-9_-]`: `openssl rand -hex 32` |
| `JIRA_URL` | `https://jira.asgardia.uz` |
| `JIRA_DEVELOPER_FIELD` | `customfield_10200` |
| `JIRA_DEVELOPER_USERNAME` | `i.fedulaev` |
| `JIRA_LABELS` | `QA` |
| `JIRA_PROJECTS` | `QDH,11000,FDH,10704` |
| `MAX_CREATE_ISSUE_BODY` | `1MB` (larger "create issue" bodies get 413; other requests are streamed, not limited) |

## Security requirements

- **HTTPS only.** Allure sends a Jira Personal Access Token (Jira admin rights) in every request through this
  service. Do not expose plain HTTP publicly.
- **Do not log request headers** at the ingress (the `Authorization` header carries the token).
  The app itself never logs headers or tokens.
- Every path except `/actuator/health` must start with `/<PATH_SECRET>` (`/<PATH_SECRET>/rest/**`,
  `/<PATH_SECRET>/browse/**`, ...); anything else returns 404. The secret is stripped before forwarding to Jira.
- **Do not log request paths** at the ingress, or treat those logs as secret: the path carries `PATH_SECRET`.
  The app itself never logs it.
- Ingress timeouts: at least 60 s (Jira calls time out at 60 s in the app).
- Request body limit: allow at least 100 MB if attachments will be sent to Jira via Allure
  (Jira's own limit is 100 MB).

## After deployment

Send `https://<public URL>/<PATH_SECRET>` to the Allure admin over a private channel. Logs to check after the first test defect:
`Create issue in <project> (added [...]) -> 201 {"key":"QDH-..."}`
