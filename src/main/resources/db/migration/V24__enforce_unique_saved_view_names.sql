CREATE UNIQUE INDEX uq_saved_view_user_name_ci
    ON saved_view (user_id, lower(name));

DROP INDEX idx_saved_view_user_id;
