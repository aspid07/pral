FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
# Без этого конфига статика отдаётся, но /api и /ws бьют в никуда — vite.config.ts
# проксирует их только в dev-режиме (`npm run dev`), а не в собранном бандле.
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
