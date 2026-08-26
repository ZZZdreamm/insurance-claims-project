import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import './globals.css';
import { AuthProvider } from './auth';

export const metadata: Metadata = { title: 'Claims Platform' };

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="pl">
      <head><link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap" /></head>
      <body><AuthProvider>{children}</AuthProvider></body>
    </html>
  );
}
