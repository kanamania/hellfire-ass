import type { NextConfig } from 'next';

// Fixed at build time: the phone reaches the sync API through this site's own origin.
const api = process.env.API_URL ?? 'http://localhost:3001';

const config: NextConfig = {
  output: 'standalone',
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
      ],
    }];
  },
};

export default config;
