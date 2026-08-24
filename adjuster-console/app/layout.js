import './globals.css';

export const metadata = { title: 'Adjuster console' };

export default function RootLayout({ children }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
