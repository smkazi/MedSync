package com.hms.identity.web;

import com.hms.identity.service.KeyService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the public signing keys. Every other service points
 * {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri} here and validates tokens offline.
 */
@RestController
public class JwksController {

    private final KeyService keys;

    public JwksController(KeyService keys) {
        this.keys = keys;
    }

    @GetMapping({"/.well-known/jwks.json", "/oauth2/jwks"})
    public Map<String, Object> jwks() {
        return keys.publicJwkSet().toJSONObject();
    }
}
