FROM ghcr.io/puppeteer/puppeteer:25.17.0
USER root
RUN usermod --uid 10001 pptruser && chown -R pptruser:pptruser /home/pptruser \
    && mkdir -p /app/web-state /app/web-session \
    && chown -R pptruser:pptruser /app
WORKDIR /app
COPY package.json package-lock.json ./
ENV PUPPETEER_SKIP_DOWNLOAD=true
RUN npm ci --omit=dev --omit=optional --ignore-scripts --no-audit --no-fund
COPY src ./src
USER pptruser
CMD ["node", "src/main.js"]
