"use server";

import { redirect } from "next/navigation";
import { readForm, refused } from "@/lib/form";
import { submit } from "@/lib/mutate";
import { clearSession } from "@/lib/session";
import { PASSWORD_FIELDS, type PasswordState } from "./state";

/**
 * Changing your own password.
 *
 * <p>The platform refuses a change that is not really one, and refuses one made without the
 * current password — both server-side, both with their own message. Nothing is re-implemented
 * here; the only rule this file owns is the confirmation field, which the API has no opinion about
 * because it never sees it.
 *
 * <p>On success the session is cleared and the user signs in again. That is not tidiness: the
 * platform revokes every refresh token for the account when the password changes, so the cookies
 * this browser holds are already dead. Keeping them would produce a signed-in-looking UI whose
 * every request fails.
 */
export async function changePassword(
  _previous: PasswordState,
  form: FormData,
): Promise<PasswordState> {
  const values = readForm(form, PASSWORD_FIELDS);
  // Never echo a password back into the rendered form, even on a refusal.
  const blank = { currentPassword: "", newPassword: "", confirmPassword: "" };

  if (values.newPassword !== values.confirmPassword) {
    return {
      values: blank,
      fieldErrors: { confirmPassword: "This does not match the new password." },
      error: null,
      done: null,
    };
  }

  const result = await submit<{ message: string }>("/auth/change-password", "POST", {
    currentPassword: values.currentPassword,
    newPassword: values.newPassword,
  });
  if (!result.ok) {
    return { ...refused(blank, result), values: blank };
  }

  await clearSession();
  redirect("/login?done=Password+changed.+Sign+in+with+the+new+one.");
}
