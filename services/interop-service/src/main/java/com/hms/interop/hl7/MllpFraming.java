package com.hms.interop.hl7;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * MLLP: the three bytes that tell one HL7 message from the next on a TCP socket.
 *
 * <p>A message is wrapped {@code <VT> ... <FS><CR>}. That is the whole protocol, and it exists
 * because HL7 v2 has no length prefix — without the wrapper a reader cannot tell a message that has
 * ended from one that is still arriving, and a carriage return means "next segment" everywhere
 * inside the payload.
 *
 * <p>This is a byte-level accumulator rather than a line reader, deliberately. TCP does not deliver
 * messages, it delivers bytes: two messages arrive in one read, one message arrives in five, and a
 * reader that assumes otherwise works on a test harness and splits a result in half on a busy
 * interface. Everything before a start byte is discarded, which is how a stream resynchronises
 * after a sender is killed mid-message.
 */
public final class MllpFraming {

    /** Start of block. */
    public static final byte VT = 0x0B;
    /** End of block. */
    public static final byte FS = 0x1C;
    /** Carriage return, which follows FS. */
    public static final byte CR = 0x0D;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private boolean inMessage;

    /** Wraps one message for sending. */
    public static byte[] frame(String message, Charset charset) {
        byte[] body = message.getBytes(charset);
        byte[] framed = new byte[body.length + 3];
        framed[0] = VT;
        System.arraycopy(body, 0, framed, 1, body.length);
        framed[body.length + 1] = FS;
        framed[body.length + 2] = CR;
        return framed;
    }

    /**
     * Feeds whatever arrived and returns the messages that are now complete.
     *
     * <p>Returns a list rather than one message because a single read can carry several, and
     * dropping the second is a result that is never filed. Partial content stays buffered for the
     * next call.
     */
    public List<String> feed(byte[] chunk, int length, Charset charset) {
        List<String> complete = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            byte b = chunk[i];
            if (!inMessage) {
                // Anything outside a message is noise: a keep-alive, a half message from a sender
                // that died, a stray newline. Skipping to the next start byte is what lets a
                // long-lived connection recover instead of failing every message after the first
                // bad one.
                if (b == VT) {
                    inMessage = true;
                    buffer.reset();
                }
                continue;
            }
            if (b == FS) {
                complete.add(buffer.toString(charset));
                buffer.reset();
                inMessage = false;
                continue;
            }
            buffer.write(b);
        }
        return complete;
    }

    /** How much of a message is buffered, for a reader enforcing a size limit. */
    public int pending() {
        return buffer.size();
    }

    /** Drops anything half-received, for a connection being reset. */
    public void reset() {
        buffer.reset();
        inMessage = false;
    }
}
