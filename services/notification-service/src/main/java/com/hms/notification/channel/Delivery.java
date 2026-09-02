package com.hms.notification.channel;

/**
 * What a channel did.
 *
 * @param sent      whether it left the platform
 * @param address   where it went, as the channel resolved it — the phone number for SMS, the
 *                  mailbox for email. Recorded rather than re-derived, because "which address did
 *                  it actually go to" is the question asked when a patient says they got nothing.
 * @param detail    the channel's own words on a failure, or a short note on success
 */
public record Delivery(boolean sent, String address, String detail) {

    public static Delivery sent(String address, String detail) {
        return new Delivery(true, address, detail);
    }

    public static Delivery failed(String address, String detail) {
        return new Delivery(false, address, detail);
    }
}
