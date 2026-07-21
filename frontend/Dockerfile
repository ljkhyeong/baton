FROM node:22-alpine AS build

WORKDIR /workspace/frontend

COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend/index.html frontend/tsconfig.json frontend/tsconfig.app.json frontend/tsconfig.node.json frontend/vite.config.ts ./
COPY frontend/public ./public
COPY frontend/src ./src

RUN npm run build

FROM caddy:2.10-alpine

COPY ops/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /workspace/frontend/dist /srv

RUN BATON_HOST=baton.invalid caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
