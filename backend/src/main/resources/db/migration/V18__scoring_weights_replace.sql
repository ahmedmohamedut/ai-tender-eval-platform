-- P3-3: the Chair replaces the weights of the model being edited. These rows are working
-- configuration until a baseline is confirmed (then a locked copy is made and a trigger refuses
-- every change), so the application role may delete them, for the Chair, while unlocked.
GRANT DELETE ON scoring_category_weights TO tender_app;
CREATE POLICY scoring_weights_delete ON scoring_category_weights FOR DELETE TO tender_app
    USING (EXISTS (SELECT 1 FROM scoring_models s WHERE s.id = scoring_model_id
                   AND s.baseline_id IS NULL AND app_role() = 'member' AND app_is_chair_of(s.tender_id)));
