-- A starter list for the managed category list (UX-REQ-04), so that extraction can assign a
-- category and the Chair can confirm a baseline (UX-REQ-13) on a new installation. Seeded only
-- while the table is empty; an administrator renames, reorders or deactivates them afterwards
-- (a category in use is deactivated, never deleted).
INSERT INTO requirement_categories (name, sort_order)
SELECT d.name, d.sort_order
  FROM (VALUES ('Functional', 1), ('Technical', 2), ('Security', 3), ('Integration', 4),
               ('Support and maintenance', 5), ('Commercial', 6), ('Compliance', 7))
       AS d (name, sort_order)
 WHERE NOT EXISTS (SELECT 1 FROM requirement_categories);
