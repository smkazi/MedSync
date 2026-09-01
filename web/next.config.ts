import type { NextConfig } from "next";

/**
 * Security headers that do not vary per request. The Content-Security-Policy is deliberately NOT
 * here: it needs a fresh nonce per response so Next's inline bootstrap script can run while
 * everything else stays blocked, so it is set in middleware.ts instead.
 */
const securityHeaders = [
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "X-Frame-Options", value: "DENY" },
  // `strict-origin-when-cross-origin`, not `no-referrer`.
  //
  // `no-referrer` looks stricter and is, but it also suppresses the `Origin` header on a form POST
  // - Chromium sends `Origin: null` - and Next's Server Action CSRF check compares `Origin` against
  // `x-forwarded-host`, so every native form submission was rejected with "Invalid Server Actions
  // request" before it reached an action. This policy still sends no referrer cross-origin beyond
  // the bare origin, and nothing at all on a downgrade to HTTP, which is the part that mattered.
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  { key: "Cross-Origin-Opener-Policy", value: "same-origin" },
  { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
  // HSTS matters only over TLS, and is harmless on plain HTTP in development.
  { key: "Strict-Transport-Security", value: "max-age=31536000; includeSubDomains" },
];

const nextConfig: NextConfig = {
  reactStrictMode: true,
  poweredByHeader: false,
  // Emits a self-contained server bundle, so the runtime image needs neither the build
  // toolchain nor the full node_modules tree.
  output: "standalone",
  async headers() {
    return [{ source: "/(.*)", headers: securityHeaders }];
  },
};

export default nextConfig;
