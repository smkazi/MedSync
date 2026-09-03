package com.hms.interop.service;

import com.hms.interop.domain.Hl7Exchange;
import com.hms.interop.hl7.MllpFraming;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The MLLP listener: a TCP port other hospital systems connect to.
 *
 * <p>Off unless {@code hms.interop.hl7.mllp.enabled} says otherwise, and that default is the
 * point. Every other way into this platform goes through the gateway, carries a bearer token and is
 * refused without one; this is a raw socket that accepts clinical messages from whoever can reach
 * it. **MLLP has no authentication of any kind** — the protocol is three framing bytes — so the
 * only thing standing between the port and the record is who can route to it. A deployment that
 * opens it is making a network decision, and it should have to make it on purpose.
 *
 * <p>Threads, not virtual threads or a reactor: an interface engine holds a handful of long-lived
 * connections from named systems, not ten thousand from strangers. A bounded pool with a queue is
 * the shape that matches, and it fails in the way somebody can diagnose — connections wait rather
 * than the heap filling with half-read messages.
 */
@Component
@ConditionalOnProperty(name = "hms.interop.hl7.mllp.enabled", havingValue = "true")
public class MllpListener implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MllpListener.class);

    private final Hl7IngestService ingest;
    private final int port;
    private final int maxMessageBytes;
    private final Charset charset;

    private ServerSocket serverSocket;
    private ExecutorService connections;
    private volatile boolean running;

    public MllpListener(Hl7IngestService ingest,
                        @Value("${hms.interop.hl7.mllp.port:2575}") int port,
                        @Value("${hms.interop.hl7.mllp.threads:8}") int threads,
                        @Value("${hms.interop.hl7.mllp.max-message-bytes:1048576}")
                        int maxMessageBytes,
                        @Value("${hms.interop.hl7.charset:UTF-8}") String charsetName) {
        this.ingest = ingest;
        this.port = port;
        this.maxMessageBytes = maxMessageBytes;
        this.charset = Charset.forName(charsetName);
        this.connections = Executors.newFixedThreadPool(threads);
    }

    @jakarta.annotation.PostConstruct
    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        Thread acceptor = new Thread(this::acceptLoop, "mllp-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
        log.warn("MLLP listener open on port {} — this port has no authentication; "
                + "restrict it at the network", port);
    }

    /** The port actually bound, which differs from the configured one when that was 0. */
    public int boundPort() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                connections.submit(() -> handle(socket));
            } catch (IOException ex) {
                if (running) {
                    log.error("MLLP accept failed", ex);
                }
            }
        }
    }

    /**
     * One connection, for as long as the sender keeps it open.
     *
     * <p>Senders send many messages down one connection and expect an acknowledgement after each,
     * in order, before sending the next. So this reads, replies, and keeps reading; it does not
     * close after a message, and it does not read ahead.
     */
    private void handle(Socket socket) {
        String peer = socket.getRemoteSocketAddress() == null ? "unknown"
                : socket.getRemoteSocketAddress().toString();
        MllpFraming framing = new MllpFraming();
        try (socket) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                List<String> messages = framing.feed(chunk, read, charset);
                for (String message : messages) {
                    // Never throws: whatever arrived, the sender gets an acknowledgement. A sender
                    // that receives nothing retries for ever, and a socket handler deciding what to
                    // do with an exception is one that will hang up mid-conversation.
                    String ack = ingest.receive(message, Hl7Exchange.Transport.MLLP, peer);
                    out.write(MllpFraming.frame(ack, charset));
                    out.flush();
                }
                if (framing.pending() > maxMessageBytes) {
                    // A sender that never terminates a frame would otherwise buffer without bound
                    // until the heap goes. Dropping the connection is the only honest answer: the
                    // message cannot be acknowledged because it has not arrived.
                    log.error("MLLP peer {} sent {} bytes with no frame terminator; closing",
                            peer, framing.pending());
                    return;
                }
            }
        } catch (IOException ex) {
            log.warn("MLLP connection from {} ended: {}", peer, ex.getMessage());
        }
    }

    @Override
    @jakarta.annotation.PreDestroy
    public void close() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ex) {
            log.debug("Closing the MLLP socket failed", ex);
        }
        connections.shutdown();
        try {
            // Long enough for a message being acknowledged to finish, short enough that a shutdown
            // is a shutdown. A sender mid-message gets no acknowledgement and will retry, which is
            // what it is for.
            if (!connections.awaitTermination(5, TimeUnit.SECONDS)) {
                connections.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            connections.shutdownNow();
        }
    }

    static Charset defaultCharset() {
        return StandardCharsets.UTF_8;
    }
}
