-- Saved Views (Smart Collections) for transactions
-- Allows users to create persistent, reusable views with filter criteria
-- and the ability to pin or exclude specific transactions

CREATE TABLE saved_view (
    id              UUID PRIMARY KEY,
    user_id         VARCHAR(50) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    criteria        TEXT NOT NULL,
    open_ended      BOOLEAN NOT NULL DEFAULT false,
    pinned_ids      TEXT NOT NULL DEFAULT '[]',
    excluded_ids    TEXT NOT NULL DEFAULT '[]',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_saved_view_user_id ON saved_view(user_id);
