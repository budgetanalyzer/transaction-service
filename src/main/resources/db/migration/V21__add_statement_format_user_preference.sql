-- Store per-user statement format visibility preferences.
CREATE TABLE statement_format_user_preference (
    id BIGSERIAL PRIMARY KEY,
    statement_format_id BIGINT NOT NULL REFERENCES statement_format(id),
    user_id VARCHAR(50) NOT NULL,
    hidden BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_statement_format_user_preference
        UNIQUE (statement_format_id, user_id)
);

CREATE INDEX idx_statement_format_user_preference_user_hidden
    ON statement_format_user_preference(user_id, hidden);

COMMENT ON TABLE statement_format_user_preference IS
    'Per-user visibility preferences for statement formats.';
COMMENT ON COLUMN statement_format_user_preference.hidden IS
    'Whether this user has hidden the format from normal statement format selection.';
