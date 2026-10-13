-- P2-2: operational columns the message handlers and timers set as the ingest role.
-- A version is superseded when its successor reaches ready; command_published_at records when
-- the backend last published a command for a job (watchdog, PI 4.4). Neither is member data.
GRANT UPDATE (superseded_at) ON document_versions TO tender_ai_ingest;
GRANT UPDATE (command_published_at) ON jobs TO tender_ai_ingest;
