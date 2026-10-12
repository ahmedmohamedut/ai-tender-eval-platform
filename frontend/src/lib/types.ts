import type { components } from "../api/generated/schema";

type S = components["schemas"];

export type Me = S["Me"];
export type User = S["User"];
export type Tender = S["Tender"];
export type TenderSummary = S["TenderSummary"];
export type TenderStatus = S["TenderStatus"];
export type Member = S["Member"];
export type UserRef = S["UserRef"];
export type Readiness = S["Readiness"];
export type TenderHome = S["TenderHome"];
export type ProgressBoard = S["ProgressBoard"];
export type StageDate = S["StageDate"];
export type UserRole = S["UserRole"];
export type UserStatus = S["UserStatus"];

/** A problem+json answer (RFC 9457) with the platform's stable code and trace id. */
export interface Problem {
  title: string;
  status: number;
  code: string;
  trace_id?: string;
  errors?: { field: string; code: string; message: string }[];
  access_contacts?: { name: string; email?: string | null; role: string }[];
}
