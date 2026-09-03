package com.hms.interop.client;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The default adapter: records that a bundle was built and sends nothing.
 *
 * <p>The honest default for a platform with no NHA credentials, and it is honest in a specific way
 * — {@code transmitted} is false, so the API response and the disclosure log both say the data did
 * not leave. An adapter that logged "sent" would make every test and every screen agree that
 * interoperability worked, which is the failure this module would be easiest to build.
 *
 * <p>What it logs is deliberately thin: the recipient, the consent reference, the resource count
 * and the size. Never the bundle. A log line carrying a patient's record would put PHI in the one
 * place operations staff read freely, and it would do it on the exchange path, where the volume is
 * highest.
 */
@Component
@ConditionalOnProperty(name = "hms.interop.gateway", havingValue = "LOG", matchIfMissing = true)
public class LoggingAbdmGateway implements AbdmGateway {

    private static final Logger log = LoggerFactory.getLogger(LoggingAbdmGateway.class);

    @Override
    public Outcome send(Map<String, Object> bundle, String recipient, String reference) {
        Object entries = bundle.get("entry");
        int count = entries instanceof java.util.List<?> list ? list.size() : 0;
        log.info("[abdm] would send bundle type={} resources={} to recipient={} under consent={}",
                bundle.get("type"), count, recipient, reference);
        return new Outcome(false, name(), ("Recorded locally. No ABDM gateway is configured, so "
                + "nothing was transmitted — set hms.interop.gateway and its URL to send."));
    }

    @Override
    public String name() {
        return "LOG";
    }
}
