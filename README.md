# HTTP Polling vs SSE vs WebSocket Demo

A single Java Spring Boot server serves the order page and delivers the same order changes through HTTP polling, Server-Sent Events (SSE), and WebSockets.

## Run it

Requirements: a JDK 21 or newer and an internet connection for the first run. Maven is downloaded automatically by the checked-in Maven Wrapper; you do not need to install Maven.

**Windows Command Prompt:**

```bat
run.cmd
```

**Windows PowerShell:**

```powershell
.\run.cmd
```

**macOS or Linux:**

```sh
./run.sh
```

The first launch downloads Maven and the Java dependencies. The wrapper uses the same Maven version on all three operating systems. You can also run `mvnw.cmd spring-boot:run` on Windows or `./mvnw spring-boot:run` on macOS/Linux.

Open <http://localhost:8000> in one or more browser windows. Choose a status and click **Send status update** in any window. Every connected page should update immediately. The inspector shows the connected browser count, event payload and this window’s event log. The terminal shows each connection, disconnection and broadcast.

The **Compare delivery methods** panel starts all three browser transports. HTTP polling requests `GET /api/order` on a selectable interval (250 ms, 1 s or 3 s); SSE holds `GET /events` open for server-to-client events; WebSocket holds `/ws/orders` open for two-way communication. Send a status update and compare observed delivery latency and update counts. The HTTP request count makes the polling overhead visible. These timings are a local demonstration, not a universal performance ranking.

The **Load & resource monitor** panel starts a separate Java process that opens real HTTP/SSE sockets, with targets of 1,000, 10,000 and 100,000 connections. It reports actual open server SSE connections, Spring process CPU, JVM heap use and JVM thread count, updated once per second. The generator ramps at about 1,000 connection attempts per second. Use **Stop load run** to close its sockets. The 100,000 target requires a Spring JVM heap limit of at least 16 GiB; the observed 10,000 run used about 1.1 GiB of heap. A machine with enough additional physical memory and OS file descriptors is also required, so test incrementally.

For a machine with ample RAM, raise the JVM heap when launching. On macOS/Linux use `JAVA_TOOL_OPTIONS=-Xmx16g ./run.sh`; in PowerShell use `$env:JAVA_TOOL_OPTIONS='-Xmx16g'; .\run.cmd`; in Command Prompt use `set JAVA_TOOL_OPTIONS=-Xmx16g && run.cmd`. The app blocks the 100,000 target when its configured heap limit is below 16 GiB.

Build a runnable jar with `mvnw.cmd package` on Windows or `./mvnw package` on macOS/Linux, then launch it with `java -jar target/sse-order-demo-1.0.0.jar`.

## What to watch

1. HTTP polling repeats `GET /api/order`; the response only arrives when the browser asks.
2. SSE opens `GET /events`; Spring creates an `SseEmitter` and pushes `order_update` events over that HTTP response.
3. WebSocket opens `/ws/orders`; the protocol supports two-way messages, while this order demo uses the connection to receive server updates.
4. Clicking **Send status update** sends `POST /api/advance`. Spring changes the in-memory order and broadcasts the same update over SSE and WebSocket. The next HTTP poll observes the new order.
5. Closing a window closes its SSE and WebSocket connections. HTTP polling stops with the page.

Endpoints: `GET /api/order`, `GET /events` (`text/event-stream`), WebSocket `/ws/orders`, and `POST /api/advance` with JSON such as `{"status":"Preparing"}`.

The order is kept in memory and resets when the server restarts. This is an educational local demo, not a production order service.
