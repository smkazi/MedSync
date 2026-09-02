"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { flushSync } from "react-dom";
import type { Menu } from "@/lib/menu";

/**
 * The top navigation, with dropdown submenus.
 *
 * <p><strong>A disclosure widget, not a hover menu.</strong> Dropdowns were chosen for this app, and
 * a dropdown opened by hover is the version that fails: it is unreachable by keyboard, it cannot be
 * opened at all on the tablets and wall-mounted terminals this runs on, and it opens by accident
 * when a pointer crosses it. So every menu is a real button:
 *
 * <ul>
 *   <li>click, Enter or Space toggles it, and hovering does nothing</li>
 *   <li>Escape closes and returns focus to the trigger, which is where the user was</li>
 *   <li>ArrowDown / ArrowUp move through the items and wrap</li>
 *   <li>ArrowDown on a closed trigger opens it and lands on the first item</li>
 *   <li>a click outside closes it; so does navigating</li>
 *   <li>`aria-expanded` and `aria-controls` are wired, so a screen reader announces the state</li>
 * </ul>
 *
 * <p>The menu <em>structure</em> is filtered on the server and passed in already trimmed. This
 * component never sees an item the signed-in user may not reach, so nothing about other roles'
 * access is serialised into the page.
 */
/** Stable id shared by the trigger's aria-controls, the panel, and the keyboard lookup. */
function panelIdFor(label: string): string {
  return `menu-${label.toLowerCase().replace(/\s+/g, "-")}`;
}

export function MenuBar({ menus }: { menus: Menu[] }) {
  /**
   * Which menu is open, and the path it was opened on.
   *
   * <p>Storing the path alongside the label makes "navigating closes the menu" a derived fact rather
   * than an effect: once the pathname changes, the recorded one no longer matches and the menu reads
   * as closed. The first version did this with a `useEffect` calling `setOpenLabel(null)`, which
   * React's lint rightly rejects - a state write in an effect is a second render pass for something
   * that was already knowable during the first.
   */
  const [openAt, setOpenAt] = useState<{ label: string; path: string } | null>(null);
  const pathname = usePathname();
  const openLabel = openAt && openAt.path === pathname ? openAt.label : null;

  const setOpenLabel = useCallback(
    (label: string | null) => setOpenAt(label ? { label, path: pathname } : null),
    [pathname],
  );

  const navRef = useRef<HTMLElement | null>(null);
  const triggerRefs = useRef(new Map<string, HTMLButtonElement>());

  const close = useCallback((focusTrigger: string | null = null) => {
    setOpenAt(null);
    if (focusTrigger) {
      triggerRefs.current.get(focusTrigger)?.focus();
    }
  }, []);

  useEffect(() => {
    if (!openLabel) return;

    function onPointerDown(event: PointerEvent) {
      if (navRef.current && !navRef.current.contains(event.target as Node)) {
        setOpenAt(null);
      }
    }
    // Capture, so an outside click closes the menu even if the target stops propagation.
    document.addEventListener("pointerdown", onPointerDown, true);
    return () => document.removeEventListener("pointerdown", onPointerDown, true);
  }, [openLabel]);

  /**
   * The links in one open panel, read from the DOM.
   *
   * <p>Queried rather than collected into a ref array. Building that array meant writing to a ref
   * during render, which React forbids for good reason - the render pass would be mutating state the
   * next render reads. The panel is the source of truth for what is in it, and it is already in the
   * document by the time a key lands.
   */
  function itemsOf(label: string): HTMLAnchorElement[] {
    const panel = document.getElementById(panelIdFor(label));
    return panel ? Array.from(panel.querySelectorAll<HTMLAnchorElement>("a[href]")) : [];
  }

  function focusItem(label: string, index: number) {
    const items = itemsOf(label);
    if (items.length === 0) return;
    // Wraps in both directions: a keyboard user should not have to know where the list ends.
    items[(index + items.length) % items.length]?.focus();
  }

  /**
   * Opens a menu and puts focus on one of its items, in one keystroke.
   *
   * <p>{@code flushSync} rather than a {@code requestAnimationFrame} callback, and that is a bug
   * fix rather than a preference. The panel's items do not exist in the DOM on the frame the key
   * fires, so the first version deferred the focus call to the next paint - which meant a keyboard
   * user who pressed Enter and then ArrowDown faster than one frame had the ArrowDown land on the
   * trigger, where it re-opened the already-opening menu and scheduled the same focus again. One
   * keystroke silently vanished: Enter, ArrowDown, ArrowDown, ArrowDown reached the third item
   * instead of the fourth. It went unnoticed locally, where a frame is quick, and failed twice in a
   row in CI, where it is not - which is the same machine class as the wall-mounted terminals this
   * is meant to run on.
   *
   * <p>Committing synchronously means the panel is in the document before this function returns, so
   * the focus is settled before the browser dispatches the next key. Nothing is deferred and there
   * is no frame to lose the race against.
   */
  function openAndFocus(label: string, index: number) {
    if (openLabel === label) {
      // Already open, so the items are already in the document.
      focusItem(label, index);
      return;
    }
    flushSync(() => setOpenLabel(label));
    focusItem(label, index);
  }

  function onTriggerKeyDown(event: React.KeyboardEvent<HTMLButtonElement>, label: string) {
    if (event.key === "ArrowDown" || event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      openAndFocus(label, 0);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      openAndFocus(label, -1);
    } else if (event.key === "Escape") {
      close();
    }
  }

  function onItemKeyDown(event: React.KeyboardEvent<HTMLAnchorElement>, label: string, index: number) {
    if (event.key === "ArrowDown") {
      event.preventDefault();
      focusItem(label, index + 1);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      focusItem(label, index - 1);
    } else if (event.key === "Escape") {
      event.preventDefault();
      close(label);
    } else if (event.key === "Tab") {
      // Tabbing out of the list is a deliberate exit, so let it through but stop showing the menu.
      setOpenAt(null);
    }
  }

  return (
    <nav ref={navRef} aria-label="Main" className="flex flex-1 flex-wrap gap-1">
      {menus.map((menu) => {
        if (!menu.items) {
          const active = pathname === menu.href;
          return (
            <Link
              key={menu.label}
              href={menu.href ?? "/"}
              aria-current={active ? "page" : undefined}
              className={`rounded-md px-3 py-1.5 text-sm ${
                active ? "bg-surface text-ink" : "text-ink-muted hover:bg-surface hover:text-ink"
              }`}
            >
              {menu.label}
            </Link>
          );
        }

        const open = openLabel === menu.label;
        const panelId = panelIdFor(menu.label);
        const active = menu.items.some(
          (item) => pathname === item.href || pathname.startsWith(`${item.href}/`),
        );
        return (
          <div key={menu.label} className="relative">
            <button
              type="button"
              ref={(node) => {
                if (node) triggerRefs.current.set(menu.label, node);
                else triggerRefs.current.delete(menu.label);
              }}
              aria-expanded={open}
              aria-controls={panelId}
              aria-haspopup="true"
              onClick={() => setOpenLabel(open ? null : menu.label)}
              onKeyDown={(event) => onTriggerKeyDown(event, menu.label)}
              className={`rounded-md px-3 py-1.5 text-sm ${
                open || active
                  ? "bg-surface text-ink"
                  : "text-ink-muted hover:bg-surface hover:text-ink"
              }`}
            >
              {menu.label}
              <span aria-hidden="true" className="ml-1 text-[0.6rem]">
                ▾
              </span>
            </button>

            {open && (
              <div
                id={panelId}
                className="absolute left-0 z-20 mt-1 min-w-64 rounded-md border border-line bg-surface-raised p-1 shadow-lg"
              >
                {menu.items.map((item, index) => (
                  <Link
                    key={item.href}
                    href={item.href}
                    onKeyDown={(event) => onItemKeyDown(event, menu.label, index)}
                    aria-current={pathname === item.href ? "page" : undefined}
                    className="block rounded px-3 py-2 text-sm text-ink hover:bg-surface focus:bg-surface focus:outline-none"
                  >
                    <span className="flex items-center justify-between gap-3">
                      <span>{item.label}</span>
                      {item.notBuilt && (
                        // Said on the item, not just on the page it leads to. Somebody scanning the
                        // menu for a feature deserves to know before they click.
                        <span className="rounded border border-line px-1.5 py-0.5 text-[0.65rem] uppercase tracking-wide text-ink-muted">
                          not built
                        </span>
                      )}
                    </span>
                    {item.note && (
                      <span className="mt-0.5 block text-xs text-ink-muted">{item.note}</span>
                    )}
                  </Link>
                ))}
              </div>
            )}
          </div>
        );
      })}
    </nav>
  );
}
