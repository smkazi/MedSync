/** Field names for the messaging forms. See `appointments/state.ts` for why this is its own module. */
export const START_THREAD_FIELDS = ["subject", "departmentCode", "body"] as const;

export const REPLY_FIELDS = ["threadId", "body"] as const;
