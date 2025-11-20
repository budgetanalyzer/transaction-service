-- Add deleted_by column to track who performed soft deletes
-- This supports the enhanced SoftDeletableEntity in service-common

ALTER TABLE transaction ADD COLUMN deleted_by VARCHAR(50);

COMMENT ON COLUMN transaction.deleted_by IS 'User ID of who performed the soft delete';
