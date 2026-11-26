package dev.jefe.mailsorter.infra.web;

import com.sun.net.httpserver.HttpExchange;
import dev.jefe.mailsorter.application.port.out.EventPublisher;
import dev.jefe.mailsorter.infra.logging.AppLog;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Server-Sent Events fan-out. Each subscriber holds one open HTTP response; the handler thread
 * (a virtual thread) parks in {@link #subscribe} sending heartbeats until the client goes away.
 */
public final class SseBroadcaster implements EventPublisher {

    private static final Duration HEARTBEAT = Duration.ofSeconds(20);

    private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();

    @Override
    public void publish(String type, String message) {
        String frame = "event: " + oneLine(type) + "\ndata: " + oneLine(message) + "\n\n";
        for (Client c : clients) {
            c.send(frame);
        }
    }

    /** Blocks until the client disconnects. */
    void subscribe(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);                     // 0 = chunked, stays open

        var client = new Client(exchange.getResponseBody());
        clients.add(client);
        try {
            client.send(": connected\n\n");
            while (client.alive()) {
                if (client.closed.await(HEARTBEAT.toMillis(), TimeUnit.MILLISECONDS)) break;
                client.send(": ping\n\n");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            clients.remove(client);
        }
    }

    int subscriberCount() {
        return clients.size();
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace('\r', ' ').replace('\n', ' ');
    }

    private static final class Client {
        private final OutputStream out;
        private final CountDownLatch closed = new CountDownLatch(1);

        Client(OutputStream out) { this.out = out; }

        boolean alive() { return closed.getCount() > 0; }

        synchronized void send(String frame) {
            if (!alive()) return;
            try {
                out.write(frame.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                AppLog.warn(SseBroadcaster.class, "SSE client gone: " + e.getMessage());
                closed.countDown();
            }
        }
    }
}
