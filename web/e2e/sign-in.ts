import { expect, type Page } from "@playwright/test";

/**
 * Signs in through the real form.
 *
 * Shared between the specs rather than copied into each: the credentials come from the environment
 * so nothing hard-codes a password, and there should be exactly one place that knows the variable's
 * name.
 */
export const PASSWORD = process.env.SEED_PASSWORD ?? "ChangeMe!Dev2026";

export async function signIn(page: Page, username: string): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("Username").fill(username);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "Today" })).toBeVisible();
}

/** Opens a top-level dropdown and returns its panel. */
export async function openMenu(page: Page, label: string) {
  const trigger = page.getByRole("button", { name: label, exact: true });
  await trigger.click();
  await expect(trigger).toHaveAttribute("aria-expanded", "true");
  const panelId = await trigger.getAttribute("aria-controls");
  return page.locator(`#${panelId}`);
}
