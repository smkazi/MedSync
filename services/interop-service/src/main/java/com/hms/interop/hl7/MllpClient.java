package com.hms.interop.hl7;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.List;

/**
 * Sends one message over MLLP and waits for the acknowledgement.
 *
 * <p>A connection per message, closed afterwards. A persistent connection is what a high-volume
 * interface uses and it brings reconnect, keep-alive and half-open detection with it; at the volume
 * a hospital sends registrations and results, connecting each time is a few milliseconds and
 * removes an entire category of failure — the socket that is open, dead, and silently swallowing
 * messages until somebody notices the far end has had nothing all afternoon.
 *
 * <p>Both timeouts matter and for different reasons. Without a connect timeout a host that drops
 * packets hangs the caller for the operating system's default, which can be minutes. Without a read
 * timeout a receiver that accepts the connection and never replies hangs it for ever, and that is
 * the more common failure: a listener whose worker pool is exhausted still completes the handshake.
 */
public final class MllpClient {

    private MllpClient() {
    }

    /** What came back, or an explanation of why nothing did. */
    public record Result(String acknowledgement, String error) {

        public boolean ok() {
            return error == null;
        }
    }

    public static Result send(String host, int port, String message, Charset charset,
                              int connectTimeoutMillis, int readTimeoutMillis) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            socket.setSoTimeout(readTimeoutMillis);

            OutputStream out = socket.getOutputStream();
            out.write(MllpFraming.frame(message, charset));
            out.flush();

            MllpFraming framing = new MllpFraming();
            InputStream in = socket.getInputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) {
                List<String> complete = framing.feed(chunk, read, charset);
                if (!complete.isEmpty()) {
                    return new Result(complete.get(0), null);
                }
            }
            // The far end closed without completing a frame. Distinguished from a timeout because
            // it means something different: the receiver decided to hang up, rather than being slow.
            return new Result(null, "The receiver closed the connection without acknowledging");
        } catch (IOException ex) {
            return new Result(null, "%s: %s".formatted(ex.getClass().getSimpleName(),
                    ex.getMessage()));
        }
    }
}
