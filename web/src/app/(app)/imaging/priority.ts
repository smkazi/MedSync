import type { BadgeTone } from "@/components/ui";
import type { ImagingPriority } from "@/lib/types";

/**
 * How a priority is coloured, in one place because two lists show it.
 *
 * <p>STAT is critical and URGENT is a warning, which is the distinction a department reads the
 * column for: one means do this now and the other means do this before the routine list. One tone
 * for both would collapse them, and the worklist and the reporting queue disagreeing about which
 * red means what would be worse than either choice.
 */
export const PRIORITY_TONES: Record<ImagingPriority, BadgeTone> = {
  ROUTINE: "neutral",
  URGENT: "warn",
  STAT: "critical",
};
