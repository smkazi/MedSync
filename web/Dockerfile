# web. Built as a standalone Next server so the runtime image carries neither the build
# toolchain nor the full node_modules tree.

FROM node:22-slim AS deps
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

FROM node:22-slim AS build
WORKDIR /build
COPY --from=deps /build/node_modules node_modules
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

FROM node:22-slim
LABEL org.opencontainers.image.title="medsync-web"
RUN useradd --system --uid 10001 --create-home medsync
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1
COPY --from=build --chown=medsync:medsync /build/.next/standalone ./
COPY --from=build --chown=medsync:medsync /build/.next/static ./.next/static
COPY --from=build --chown=medsync:medsync /build/public ./public
USER 10001

EXPOSE 3000
HEALTHCHECK --interval=15s --timeout=3s --start-period=15s --retries=5 \
    CMD ["node", "-e", "require('http').get('http://127.0.0.1:3000/login', r => process.exit(r.statusCode === 200 ? 0 : 1)).on('error', () => process.exit(1))"]
CMD ["node", "server.js"]
