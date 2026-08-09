ARG CADDY_BUILDER_IMAGE=caddy:2.11.4-builder-alpine
ARG CADDY_IMAGE=caddy:2.11.4-alpine
ARG CADDY_VERSION=v2.11.4
ARG CADDY_RATE_LIMIT_MODULE=github.com/mholt/caddy-ratelimit@5625512f24f6f59d6f64fb3aafe5eecff0b286db

FROM node:22-alpine AS build

WORKDIR /workspace/frontend

COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend/index.html frontend/tsconfig.json frontend/tsconfig.app.json frontend/tsconfig.node.json frontend/vite.config.ts ./
COPY frontend/public ./public
COPY frontend/src ./src

RUN npm run build

FROM ${CADDY_BUILDER_IMAGE} AS caddy-build
ARG CADDY_VERSION
ARG CADDY_RATE_LIMIT_MODULE
RUN GOTOOLCHAIN=local xcaddy build "${CADDY_VERSION}" \
    --output /usr/bin/caddy \
    --with "${CADDY_RATE_LIMIT_MODULE}" \
    && caddy list-modules | grep -Fxq 'http.handlers.rate_limit'

FROM ${CADDY_IMAGE}

COPY --from=caddy-build /usr/bin/caddy /usr/bin/caddy
COPY ops/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /workspace/frontend/dist /srv

RUN command -v setcap >/dev/null \
    && command -v getcap >/dev/null \
    && { setcap -r /usr/bin/caddy 2>/dev/null || true; } \
    && test -z "$(getcap /usr/bin/caddy)" \
    && BATON_HOST=baton.invalid \
    BATON_ROUND_RUNTIME_ENABLED=false \
    caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile \
    && caddy list-modules | grep -Fxq 'http.handlers.rate_limit'
