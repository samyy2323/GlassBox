-- V10: an experiment may measure a fixed random sample of the test set instead of all of it (full runs generate and judge every answer,
-- which is slow on a small local model). The sample depends only on the question ids, so every run of the experiment sees the same questions.
ALTER TABLE experiments ADD COLUMN question_limit int CHECK (question_limit IS NULL OR question_limit > 0);
