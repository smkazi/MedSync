"use server";

import { revalidatePath } from "next/cache";
import { readForm, refused, withoutBlanks, type FormState } from "@/lib/form";
import { submit } from "@/lib/mutate";
import type { AdminUser, Department, Staff } from "@/lib/types";

/**
 * Administration: departments, the staff directory, and platform accounts.
 *
 * <p>All `ADMIN_ONLY`. Two of these carry a rule worth naming rather than a shape worth reusing.
 *
 * <p><strong>Roles are a set, so they are the one field that is not sparse.</strong> Every other
 * update here drops a blank field as "leave it alone"; sending no roles has to mean "leave them
 * alone" too, because sending an empty set would strip an account's access silently. The form
 * therefore submits the boxes that are ticked and the action sends `roles` only when at least one
 * is — taking every role off an account is done by deactivating it, which says what it means.
 *
 * <p><strong>A staff record and a login are separate things.</strong> `userId` links them and is
 * optional: a visiting consultant appears in the directory and never signs in, and an account can
 * exist with no staff row. The clinician pick-list on the booking screen reads the staff
 * directory, so a doctor who can sign in but has no staff record cannot be booked — which is why
 * the staff form offers the accounts that have no staff row yet.
 */

async function write<T>(
  path: string,
  method: "POST" | "PATCH",
  values: Record<string, string>,
  body: Record<string, unknown>,
  refresh: string[],
  done: string,
): Promise<FormState> {
  const result = await submit<T>(path, method, body);
  if (!result.ok) {
    return refused(values, result);
  }
  for (const page of refresh) {
    revalidatePath(page);
  }
  return { values: {}, fieldErrors: {}, error: null, done };
}

// ---- departments ------------------------------------------------------------

export async function createDepartment(_previous: FormState, form: FormData): Promise<FormState> {
  const values = readForm(form, ["code", "name", "description"] as const);
  return write<Department>("/departments", "POST", values, withoutBlanks(values),
    ["/facility/departments", "/appointments/new"],
    `Department ${values.code.toUpperCase()} added.`);
}

export async function updateDepartment(_previous: FormState, form: FormData): Promise<FormState> {
  const code = String(form.get("code") ?? "");
  const values = readForm(form, ["name", "description", "active"] as const);
  const body = withoutBlanks(values);
  if (values.active !== "") body.active = values.active === "true";
  // Retiring a department keeps every row that points at it: the encounters recorded under it are
  // still real, and three services store the code, which is why there is no rename.
  return write<Department>(`/departments/${code}`, "PATCH", values, body,
    ["/facility/departments", "/appointments/new"], `Department ${code} updated.`);
}

// ---- staff ------------------------------------------------------------------

const STAFF_FIELDS = [
  "employeeNo",
  "fullName",
  "designation",
  "userId",
  "departmentCode",
  "specialty",
  "licenseNo",
  "phone",
  "email",
] as const;

export async function createStaff(_previous: FormState, form: FormData): Promise<FormState> {
  const values = readForm(form, STAFF_FIELDS);
  return write<Staff>("/staff", "POST", values, withoutBlanks(values),
    ["/admin/staff", "/appointments/new", "/scheduling/availability"],
    `${values.fullName} added to the directory.`);
}

export async function updateStaff(_previous: FormState, form: FormData): Promise<FormState> {
  const id = String(form.get("id") ?? "");
  const values = readForm(form, [
    "fullName", "designation", "userId", "departmentCode",
    "specialty", "licenseNo", "phone", "email", "active",
  ] as const);
  const body = withoutBlanks(values);
  if (values.active !== "") body.active = values.active === "true";
  return write<Staff>(`/staff/${id}`, "PATCH", values, body,
    ["/admin/staff", "/appointments/new", "/scheduling/availability"], "Staff record updated.");
}

// ---- platform accounts ------------------------------------------------------

/** The roles ticked on a submitted form. Absent means "unchanged", never "none". */
function rolesFrom(form: FormData): string[] {
  return form.getAll("roles").map(String).filter((role) => role !== "");
}

export async function createUser(_previous: FormState, form: FormData): Promise<FormState> {
  const values = readForm(form, ["username", "email", "fullName", "password"] as const);
  const roles = rolesFrom(form);
  if (roles.length === 0) {
    return {
      values,
      fieldErrors: { roles: "Give the account at least one role; it can do nothing without one." },
      error: null,
      done: null,
    };
  }
  // The account is created must-change-password, so this value is a handover, not a secret the
  // administrator keeps. The service enforces that: it mints a role-less token until it changes.
  return write<AdminUser>("/admin/users", "POST", values,
    { ...withoutBlanks(values), roles },
    ["/admin/users"],
    `${values.username} created. It must change this password before it can do anything.`);
}

export async function updateUser(_previous: FormState, form: FormData): Promise<FormState> {
  const id = String(form.get("id") ?? "");
  const values = readForm(form, ["email", "fullName", "active"] as const);
  const roles = rolesFrom(form);
  const body = withoutBlanks(values);
  if (values.active !== "") body.active = values.active === "true";
  // Only when something is ticked. An empty set would strip the account's access, and the screen
  // that did that by accident would look exactly like the screen that did nothing.
  if (roles.length > 0) body.roles = roles;
  return write<AdminUser>(`/admin/users/${id}`, "PATCH", values, body,
    ["/admin/users"], "Account updated.");
}

export async function resetPassword(_previous: FormState, form: FormData): Promise<FormState> {
  const id = String(form.get("id") ?? "");
  const username = String(form.get("username") ?? "this account");
  const values = readForm(form, ["newPassword"] as const);
  // A reset revokes every session and re-flags the account, so the person whose password this is
  // has to change it before they can work. That is the same rule as a new account: whoever typed
  // it knows it.
  return write<{ message: string }>(`/admin/users/${id}/password`, "POST", values,
    { newPassword: values.newPassword },
    ["/admin/users"],
    `Password reset for ${username}. Every session is signed out and it must be changed at next sign-in.`);
}
