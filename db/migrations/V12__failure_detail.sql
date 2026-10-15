-- V12: what happened to each gold span on its way through the pipeline (rank in the fused list, rank after reranking, whether it reached the
-- prompt and why not) plus the failure label that the classifier derived from it. run_results.failure_stage keeps the stage for list views.
ALTER TABLE run_results ADD COLUMN failure_detail jsonb;
