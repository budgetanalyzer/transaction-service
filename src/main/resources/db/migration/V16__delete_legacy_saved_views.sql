-- Saved-view criteria JSON changed from startDate/endDate to dateFrom/dateTo.
-- Existing rows can no longer be deserialized safely, so drop the legacy
-- user-defined views before the application reads them.
DELETE FROM saved_view;
