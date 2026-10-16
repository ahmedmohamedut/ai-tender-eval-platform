import type { Requirement, Tender, TenderVendor, Vendor } from './types';

export const AMLOCK = 'amlock';

export const seedVendors: Vendor[] = [
  { id: 'SAS', name: 'SAS', legal: 'SAS Solutions Co.', reg: 'CR-1010000001', country: 'Saudi Arabia', industry: 'Analytics & data platforms', website: 'sas.example.com', status: 'Active', contact: 'Vendor contact 01', email: 'contact@sas.example.com', phone: '+966 11 000 0001', address: 'Riyadh, Saudi Arabia', notes: 'Incumbent platform vendor for the AMLock programme.' },
  { id: 'ABG', name: 'ABG', legal: 'ABG Technology Group', reg: 'TL-2020000002', country: 'United Arab Emirates', industry: 'IT services & integration', website: 'abg.example.com', status: 'Active', contact: 'Vendor contact 02', email: 'bids@abg.example.com', phone: '+971 4 000 0002', address: 'Dubai, United Arab Emirates', notes: '' },
  { id: 'NORTHWIND', name: 'Northwind Systems', legal: 'Northwind Systems Ltd.', reg: 'UK-30000003', country: 'United Kingdom', industry: 'Cloud infrastructure', website: 'northwind.example.com', status: 'Active', contact: 'Vendor contact 03', email: 'sales@northwind.example.com', phone: '+44 20 0000 0003', address: 'London, United Kingdom', notes: '' },
  { id: 'HELIX', name: 'Helix Cloud Services', legal: 'Helix Cloud Services GmbH', reg: 'HRB-400004', country: 'Germany', industry: 'Cloud infrastructure', website: 'helix.example.com', status: 'Inactive', contact: 'Vendor contact 04', email: 'info@helix.example.com', phone: '+49 30 0000 0004', address: 'Berlin, Germany', notes: 'Deactivated: no longer pre-qualified.' },
];

export const seedTenderVendors: TenderVendor[] = [
  { vid: 'SAS', screening: 'Accepted', eval: 'In progress', doc: 'SAS Technical Proposal.pdf' },
  { vid: 'ABG', screening: 'Accepted', eval: 'In progress', doc: 'ABG Response.pdf' },
];

export const seedTenders: Tender[] = [
  { id: AMLOCK, name: 'AMLock Migration to SAS', status: 'Finalized', mc: 2, last: '15 Oct 2026' },
  { id: 'cop', name: 'Customer Onboarding Platform', status: 'Evaluation', mc: 4, vendors: 3, last: '14 Oct 2026' },
  { id: 'dat', name: 'Data Analytics Services', status: 'Requirements', mc: 3, vendors: 0, last: '12 Oct 2026' },
];

/** Tenders other than AMLock only exist as list rows; used for the vendor "tender participation" table. */
export const otherTenderVendors: Record<string, string[]> = { cop: ['SAS', 'ABG', 'NORTHWIND'], dat: [] };

export const reqs: Requirement[] = [
  { id: 'REQ-001', name: 'Periodic vulnerability scans and penetration testing', cat: 'Security', priority: 'Mandatory', grade: 'partial', other: 'partial', p: 12 },
  { id: 'REQ-002', name: 'Backup and restore procedures with disaster recovery testing', cat: 'Operations', priority: 'Mandatory', grade: 'full', other: 'full', p: 14 },
  { id: 'REQ-003', name: 'Documented technical expertise and support experience', cat: 'Services', priority: 'Mandatory', grade: 'partial', other: 'partial', p: 19 },
  { id: 'REQ-004', name: 'Logging capabilities aligned with ISO 27001', cat: 'Security', priority: 'Mandatory', grade: 'partial', other: 'partial', p: 23 },
  { id: 'REQ-005', name: 'Encryption for sensitive data at rest and in transit', cat: 'Security', priority: 'Mandatory', grade: 'full', other: 'partial', p: 25 },
  { id: 'REQ-006', name: 'Multi-factor authentication for privileged access', cat: 'Security', priority: 'Mandatory', grade: 'partial', other: 'non', p: 31 },
  { id: 'REQ-007', name: 'Secure destruction of sensitive data', cat: 'Data governance', priority: 'Mandatory', grade: 'non', other: 'partial', p: 35 },
  { id: 'REQ-008', name: '24-hour incident reporting and escalation process', cat: 'Operations', priority: 'Mandatory', grade: 'full', other: 'full', p: 39 },
];

export const industries = ['Analytics & data platforms', 'IT services & integration', 'Cloud infrastructure', 'Cybersecurity', 'Consulting', 'Other'];
export const committeePeople = ['Member 01', 'Member 02', 'Member 03', 'Member 04', 'Member 05'];

export const notificationsData = [
  { id: 1, title: 'Final report is ready', desc: 'AMLock Migration to SAS · Report v1', to: '/tenders/amlock/report', unread: true, phase: 'finalized' as const },
  { id: 2, title: 'Proposal evaluation assigned', desc: 'Customer Onboarding Platform · 12 requirements awaiting review', to: '/tenders/cop/overview', unread: true },
  { id: 3, title: 'Requirements baseline confirmed', desc: 'AMLock Migration to SAS · Baseline v1', to: '/tenders/amlock/requirements', unread: false },
];

export const jobsData = [
  { id: 1, job: 'Proposal grading', tender: 'AMLock Migration to SAS', state: 'Completed', progress: 100, started: '14 Oct 2026', to: '/tenders/amlock/evaluation' },
  { id: 2, job: 'RFP requirement extraction', tender: 'AMLock Migration to SAS', state: 'Completed', progress: 100, started: '13 Oct 2026', to: '/tenders/amlock/requirements' },
  { id: 3, job: 'Proposal indexing', tender: 'Customer Onboarding Platform', state: 'Running', progress: 72, started: 'Today', to: '' },
];

export const usersData = [
  { id: 1, user: 'Member 01', role: 'Member', status: 'Active', last: 'Today' },
  { id: 2, user: 'Member 02', role: 'Chair in current tender', status: 'Active', last: 'Today' },
  { id: 3, user: 'Procurement Admin', role: 'Administrator', status: 'Active', last: 'Yesterday' },
];

export const categoriesData = [
  { id: 1, category: 'Security', count: 12, status: 'Active', order: 1 },
  { id: 2, category: 'Operations', count: 7, status: 'Active', order: 2 },
  { id: 3, category: 'Services', count: 4, status: 'Active', order: 3 },
  { id: 4, category: 'Data governance', count: 3, status: 'Active', order: 4 },
];

export const auditData = [
  { id: 1, date: '15 Oct 2026, 16:30', actor: 'Member 02', object: 'Final evaluation report', action: 'Finalized v1', reason: 'All pre-checks passed' },
  { id: 2, date: '15 Oct 2026, 15:10', actor: 'Member 02', object: 'REQ-006 · SAS', action: 'Final grade recorded', reason: 'Clarified evidence reviewed' },
  { id: 3, date: '13 Oct 2026, 11:20', actor: 'Member 01', object: 'Requirements baseline', action: 'Confirmed v1', reason: 'All requirements reviewed' },
];

export const rfpSections: [string, string, number, string, string, string][] = [
  ['1', 'Introduction', 3, 'This tender invites proposals for the migration of the AMLock platform to a managed analytics environment.', 'The purpose of this RFP is to select one vendor for the full migration.', 'Respondents must follow the submission instructions in section 6.'],
  ['2', 'Scope of work', 6, 'The scope covers data migration, platform configuration and user onboarding.', 'Out-of-scope items are listed in Appendix A.', 'Optional services may be priced separately.'],
  ['3', 'Functional requirements', 9, 'The platform must support role-based workflows and audit reporting.', 'Detailed functional requirements are numbered FR-01 to FR-18.', 'Each requirement must be answered individually.'],
  ['4', 'Security and compliance', 12, 'The vendor must establish appropriate access controls, change management and audit capabilities.', 'Solutions must demonstrate logging controls aligned to ISO 27001.', 'Supporting policies and operational documentation shall be included in the technical response.'],
  ['5', 'Service levels', 19, 'Service levels are measured monthly and reported to the client.', 'Incident reporting and escalation must operate within 24 hours.', 'Availability targets are defined in Appendix C.'],
  ['6', 'Submission instructions', 27, 'Proposals must be submitted as a technical and a separate commercial document.', 'Late submissions will not be evaluated.', 'Questions must be sent through the procurement portal.'],
];

export const proposalSections = (name: string): { n: string; title: string; p: number; intro: string; hl: string; end: string; restricted?: boolean }[] => [
  { n: '1', title: 'Executive summary', p: 2, intro: `${name} is pleased to submit this technical proposal for the AMLock migration.`, hl: 'Our approach combines a phased migration with continuous security validation.', end: 'Section 2 describes the delivery approach in detail.' },
  { n: '2', title: 'Technical approach', p: 8, intro: 'The solution is delivered on a managed analytics platform.', hl: 'Migration is executed in three waves with rollback checkpoints.', end: 'Architecture diagrams are provided in Appendix B.' },
  { n: '3', title: 'Security and compliance', p: 12, intro: 'Our solution includes safeguards and operational procedures.', hl: 'Periodic vulnerability scans and penetration testing is addressed through documented operational controls.', end: 'Supporting documentation includes procedures and implementation details.' },
  { n: '4', title: 'Service levels', p: 18, intro: 'Support is available around the clock.', hl: 'Incidents are reported and escalated within 24 hours.', end: 'Monthly service reports are provided to the client.' },
  { n: '5', title: 'Implementation plan', p: 24, intro: 'The implementation runs for nine months.', hl: 'Key milestones are agreed with the Chair at kickoff.', end: 'A detailed schedule is provided in Appendix D.' },
  { n: '6', title: 'Commercial proposal', p: 31, intro: '', hl: '', end: '', restricted: true },
];
