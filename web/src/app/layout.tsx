import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "MedSync",
  description: "Hospital management platform",
};

/**
 * `<html>` and `<body>`, and nothing else.
 *
 * <p>Everything that looks like an application — the navigation, who is signed in, the content
 * column — lives in the `(app)` route group's layout. That split exists for one screen: the wall
 * display at `/display/{room}` is mounted in a corridor and must have no chrome at all, and a
 * nested layout can add to its parent but never take the parent's markup away. Route groups do not
 * appear in URLs, so nothing moved as far as a browser is concerned.
 */
export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body className="min-h-dvh antialiased">{children}</body>
    </html>
  );
}
