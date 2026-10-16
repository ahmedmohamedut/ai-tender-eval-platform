export type Role = 'Administrator' | 'Member' | 'Chair' | 'Observer';
export type Phase = 'evaluation' | 'finalized';
export type GradeKey = 'full' | 'partial' | 'non';

export interface Vendor {
  id: string; name: string; legal: string; reg: string; country: string; industry: string;
  website: string; status: 'Active' | 'Inactive'; contact: string; email: string; phone: string;
  address: string; notes: string;
}
export type ScreeningStatus = 'Pending' | 'Accepted' | 'Rejected';
export interface TenderVendor {
  vid: string; screening: ScreeningStatus; eval: 'Not started' | 'In progress'; doc: string; decisionNote?: string;
}
export type TenderStatus = 'Draft' | 'Requirements' | 'Evaluation' | 'Finalized';
export interface Tender {
  id: string; name: string; status: TenderStatus; mc?: number; members?: string[]; chair?: string;
  desc?: string; due?: string; rfp?: string; vendors?: number; last: string; created?: boolean;
}
export interface Requirement {
  id: string; name: string; cat: string; priority: 'Mandatory'; grade: GradeKey; other: GradeKey; p: number;
}
