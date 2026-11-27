import type { NextConfig } from 'next';

// Fixed at build time: the phone reaches the sync API through this site's own origin.
const api = process.env.API_URL ?? 'http://127.0.0.1:3911';

// Next's hydration uses inline scripts and styles. News images come from publishers (https:);
// library media is decrypted in the browser and shown from blob: URLs. Dev mode also needs eval.
const csp = [
  "default-src 'self'",
  `script-src 'self' 'unsafe-inline'${process.env.NODE_ENV === 'production' ? '' : " 'unsafe-eval'"}`,
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' https: data: blob:",
  "media-src 'self' blob:",
  "frame-src blob:",
  "connect-src 'self'",
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "frame-ancestors 'none'",
].join('; ');

const config: NextConfig = {
  poweredByHeader: false,
  async rewrites() {
    return [{ source: '/api/sync/:path*', destination: `${api}/api/sync/:path*` }];
  },
  async headers() {
    return [{
      source: '/:path*',
      headers: [
        { key: 'X-Frame-Options', value: 'DENY' },
        { key: 'X-Content-Type-Options', value: 'nosniff' },
        { key: 'Referrer-Policy', value: 'no-referrer' },
        { key: 'Content-Security-Policy', value: csp },
      ],
    }];
  },
};

export default config;
