DELETE FROM saved_view;

ALTER TABLE saved_view DROP COLUMN criteria;
ALTER TABLE saved_view DROP COLUMN open_ended;
ALTER TABLE saved_view DROP COLUMN pinned_ids;
ALTER TABLE saved_view DROP COLUMN excluded_ids;

CREATE TABLE saved_view_transaction (
    view_id UUID NOT NULL,
    transaction_id BIGINT NOT NULL,
    PRIMARY KEY (view_id, transaction_id),
    CONSTRAINT fk_saved_view_transaction_view
        FOREIGN KEY (view_id) REFERENCES saved_view(id) ON DELETE CASCADE,
    CONSTRAINT fk_saved_view_transaction_transaction
        FOREIGN KEY (transaction_id) REFERENCES transaction(id)
);

CREATE INDEX idx_saved_view_transaction_transaction_id
    ON saved_view_transaction(transaction_id);
