import type { TenderStatus, UserRole, UserStatus } from "./types";

/**
 * The fixed vocabulary (docs/design/02-ux-design.md, Content design): one place turns a stored
 * value into its label, so the same status reads the same on every screen (UX-GEN-10).
 */
export const TENDER_STAGES: TenderStatus[] = [
  "draft",
  "requirements",
  "evaluation",
  "consolidation",
  "finalized",
];

export const tenderStatusLabel: Record<TenderStatus, string> = {
  draft: "Draft",
  requirements: "Requirements",
  evaluation: "Evaluation",
  consolidation: "Consolidation",
  finalized: "Finalized",
};

export const roleLabel: Record<UserRole, string> = {
  admin: "Administrator",
  member: "Committee member",
  observer: "Observer",
};

export const userStatusLabel: Record<UserStatus, string> = {
  invited: "Invited",
  active: "Active",
  deactivated: "Deactivated",
};

export type Tone = "neutral" | "success" | "warning" | "error" | "info";

export const tenderStatusTone: Record<TenderStatus, Tone> = {
  draft: "neutral",
  requirements: "info",
  evaluation: "info",
  consolidation: "warning",
  finalized: "success",
};

export const userStatusTone: Record<UserStatus, Tone> = {
  invited: "warning",
  active: "success",
  deactivated: "neutral",
};

/** Whole-sentence copy for the problem codes a person can act on. */
export function problemMessage(code: string | undefined, fallback: string): string {
  switch (code) {
    case "email_exists":
      return "A user with this email already exists.";
    case "name_taken":
      return "A tender with this name already exists. Choose another name.";
    case "chair_must_be_replaced":
      return "This person is the Chair of a tender. Designate another Chair first.";
    case "committee_empty":
      return "An open tender needs at least one member.";
    case "not_ready":
      return "The tender is not ready to open yet. Complete the checklist first.";
    case "last_administrator":
      return "There must be at least one active administrator.";
    case "has_memberships":
      return "Remove this person from their committees before making them an administrator.";
    case "cannot_change_own_role":
      return "You cannot change your own role.";
    case "cannot_deactivate_self":
      return "You cannot deactivate your own account.";
    case "precondition_failed":
      return "Someone else changed this item. Reload it and try again.";
    case "identity_provider_error":
      return "The sign-in service did not accept the change. Try again in a moment.";
    case "tender_archived":
      return "Archived tenders are read-only.";
    default:
      return fallback;
  }
}
