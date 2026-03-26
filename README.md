# IA-Orchestrator-Local

Developer README — how to run, debug and extend the IA Orchestrator (Blazor UI + Java orchestrator + Python worker + Docker infra).

## Project overview
This workspace contains a small orchestration system that demonstrates:

- A Blazor Web UI (UI client) for chat located in `IA-Web-Interface/`.
- A Java Spring Boot orchestrator (`orchestrator-java/ia-orchestrator`) handling HTTP requests, Jobs, persistence (Postgres) and RabbitMQ integration.
- A Python worker (`agent-python/worker.py`) that consumes tasks from RabbitMQ, calls a local model (ollama) and publishes responses.
- Docker infrastructure (`infrastructure/docker-compose.yml`) which runs RabbitMQ and Postgres used by the orchestrator.

Design decisions
- Postgres is the canonical source of truth for chat messages and jobs. The worker fetches history from the orchestrator HTTP API.
- Communication: the UI posts prompts to the orchestrator, which creates a Job and publishes a RabbitMQ task. The worker processes the task and publishes the result on `ai_responses` queue. The orchestrator saves results and emits Server-Sent Events (SSE) to push responses to clients in real time.

## Ports and endpoints (defaults)
- UI (Blazor): http://localhost:5123
- Orchestrator (Spring Boot): http://localhost:8080
  - POST `/api/ai/ask` — submit prompt (returns 202 and `X-Job-Id`) 
  - GET `/api/ai/status/{jobId}` — job status/result
  - GET `/api/ai/history/{sessionId}?limit=N` — recent messages for session
  - GET `/api/ai/stream/{sessionId}` — SSE stream for session (EventSource)
- RabbitMQ (docker): 5672 (AMQP), 15672 (management UI)
- Postgres (docker): 5432 (if remapped to 5433 this is documented below)

## Prerequisites
- Docker & docker-compose
- Java JDK 17+ and Maven
- .NET SDK 10
- Python 3.10+ and pip
- (Optional) `psql` client for inspecting Postgres from host

## Quick start — recommended order
1. Start infrastructure (RabbitMQ + Postgres)

```bash
cd /home/juve/IA-Orchestrator-Local/infrastructure
docker-compose up -d
docker ps --filter name=postgres_db --filter name=rabbitmq_local
```

If you get an error about port `5432` already in use, see "Troubleshooting: port 5432 in use" below.

2. Start the orchestrator (Java)

```bash
cd /home/juve/IA-Orchestrator-Local/orchestrator-java/ia-orchestrator
# development run (logs appear in terminal)
./mvn spring-boot:run
# or build + run jar
mvn -DskipTests clean package
java -jar target/*.jar
```

Wait for the line "Tomcat started on port 8080 (http)".

3. Start the worker (Python)

```bash
cd /home/juve/IA-Orchestrator-Local/agent-python
# optional: use a virtualenv
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt   # if present; otherwise ensure `pika` and `ollama` are available
python3 worker.py
```

The worker will fetch session history from the orchestrator (`/api/ai/history/{session}`) and publish results to RabbitMQ.

4. Start the UI (Blazor)

```bash
cd /home/juve/IA-Orchestrator-Local/IA-Web-Interface
dotnet build
dotnet run
```

Open http://localhost:5123 and test the chat.

## How the flow works (sequence)
1. Browser → POST `/api/ai/ask` (includes headers `X-Session-Id` and `X-History-Size`).
2. Orchestrator saves the user `ChatMessage`, creates a `Job` (PENDING), publishes a task to RabbitMQ and returns 202 + job id.
3. Worker consumes `ai_tasks_queue`, fetches history from orchestrator `/api/ai/history/{session}`, calls the local model (ollama), and publishes the response to `ai_responses` with headers including `job_id` and `session_id`.
4. Orchestrator `ResponsesListener` consumes from `ai_responses`, updates the `Job` (COMPLETED, result=...), persists an `ASSISTANT` ChatMessage, and emits an SSE event for the session. The SSE event is emitted only after the DB transaction commits, so clients see data that is already persisted.
5. UI listens on `/api/ai/stream/{sessionId}` via EventSource and replaces the placeholder message with the pushed result when received.

## Inspecting the database
If Postgres runs in Docker (default), use:

```bash
# list tables
docker exec -it postgres_db psql -U juve -d ai_orchestrator -c "\dt"
# view messages
docker exec -it postgres_db psql -U juve -d ai_orchestrator -c "SELECT id, session_id, role, substring(content FROM 1 FOR 200) AS snippet, timestamp FROM messages ORDER BY id DESC LIMIT 20;"
# view jobs
docker exec -it postgres_db psql -U juve -d ai_orchestrator -c "SELECT id, session_id, status, substring(prompt FROM 1 FOR 200) AS prompt_snip, substring(result FROM 1 FOR 400) AS result_snip, created_at, updated_at FROM jobs ORDER BY created_at DESC LIMIT 20;"
```

If a local Postgres instance (not Docker) uses the port, use host-side `psql` (or set `PGHOST`/`PGPORT`).

## Troubleshooting
- Error: `failed to bind host port 0.0.0.0:5432/tcp: address already in use`
  - Another Postgres instance is listening on 5432 (system service or container). Detect with:

```bash
sudo ss -ltnp | grep ':5432'
sudo lsof -iTCP:5432 -sTCP:LISTEN -P -n
```

  - Option 1: stop the host Postgres temporarily

```bash
sudo systemctl stop postgresql
# then restart infra
cd infrastructure
docker-compose up -d
```

  - Option 2: remap container port to host 5433 and update JDBC URL

Edit `infrastructure/docker-compose.yml` (change `- "5432:5432"` to `- "5433:5432"`) and update `orchestrator-java/src/main/resources/application.properties` `spring.datasource.url` to `jdbc:postgresql://localhost:5433/ai_orchestrator`.

- Worker shows `[HTTP] fetch_history failed: Connection refused` — the orchestrator is not reachable on `http://localhost:8080`. Ensure the Java app is running and listening on 8080.

- UI shows GUIDs instead of text — previously the UI polled / accepted job id as the result. The UI now uses SSE; if you still see GUIDs, check browser console for EventSource errors and server logs for SSE emission errors.

- `HttpClient.Timeout` errors in Blazor: we increased the HttpClient timeout in `Program.cs` to avoid canceled requests for long-running calls. If you still have timeout errors, verify the server and worker are operational.

## Logs & Useful commands
- Java logs (run in foreground): `./mvn spring-boot:run` (or `java -jar target/*.jar`)
- Tail container logs:

```bash
docker logs postgres_db --tail 200
docker logs rabbitmq_local --tail 200
```

- List docker containers and ports:
```bash
docker ps -a --format "table {{.ID}}\t{{.Names}}\t{{.Status}}\t{{.Ports}}"
```

## Development tips
- The orchestrator has `spring.jpa.show-sql=true` for visible SQL logs while developing.
- SSE can be tested with `curl -N http://localhost:8080/api/ai/stream/<sessionId>`; keep the connection open and then trigger a job from the UI to observe the event.
- If you change port mappings, use environment variables rather than hardcoding in code. For Spring Boot you can pass `-Dspring.datasource.url=...` on the JVM command line or use environment variables in Docker.

## Optional extras I can help add
- `run-all.sh` script to orchestrate startup of Docker, Java, worker and UI.
- Migration script to import any legacy SQLite worker history into Postgres.
- Make worker `ORCHESTRATOR_URL` configurable via environment variable and add retry/backoff logic.
- Convert SSE to WebSocket for bi-directional realtime communication.
