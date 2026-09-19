ALTER TABLE transaction ALTER COLUMN bank_name DROP NOT NULL;

COMMENT ON COLUMN transaction.bank_name IS
    'Name of the bank where the transaction occurred; null means no bank was recorded';
