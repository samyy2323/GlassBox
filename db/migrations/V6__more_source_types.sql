-- V6: documents can come from many more file types. The allowed set now lives in code (FileTypes), which is the
-- one place that knows how to classify and parse each type; a database CHECK would just have to be edited in step.

ALTER TABLE documents DROP CONSTRAINT documents_source_type_check;
ALTER TABLE documents ADD CONSTRAINT documents_source_type_shape CHECK (source_type ~ '^[A-Z0-9_]{2,16}$');
