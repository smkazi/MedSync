package com.hms.interop.client;

import java.util.Map;

/**
 * Where a bundle goes when it leaves.
 *
 * <p>A port with two adapters, the same shape notification-service uses for SMS: the platform is
 * built to work with nothing configured, and a deployment with real NHA credentials swaps the
 * adapter rather than the module. The reason is not architectural taste — it is that this
 * repository cannot be certified against ABDM from here, and a module that only compiled against a
 * real gateway would be a module nobody could run or test.
 */
public interface AbdmGateway {

    /**
     * What happened.
     *
     * @param transmitted false when nothing left the building. Reported rather than swallowed: a
     *                    disclosure log that recorded a send that did not happen would be worse
     *                    than one that recorded nothing.
     * @param detail      what the adapter did, for the response and the log. Never the bundle.
     */
    record Outcome(boolean transmitted, String name, String detail) {
    }

    /**
     * Sends one bundle to one recipient.
     *
     * @param bundle    the FHIR bundle, already built
     * @param recipient the consent's requester — a health information user in ABDM's terms
     * @param reference the consent artefact id, which is what the receiving end reconciles against
     */
    Outcome send(Map<String, Object> bundle, String recipient, String reference);

    /** The adapter's name, for the response and the disclosure log. */
    String name();
}
