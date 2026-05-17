# Saved Views

**Status:** Active
**Service:** transaction-service

## Overview

Saved views are user-owned transaction filters with optional pinned and excluded
transaction overrides. They are exposed through `/v1/views/**` and persist in
the `saved_view` table.

All saved-view membership is owner-scoped. A view cannot supply its own owner
ID; the service injects the authenticated user.

## Criteria

Saved views persist the user-facing transaction filters below in the `criteria`
object. All fields are optional.

- `dateFrom`, `dateTo` - Inclusive transaction date range.
- `searchText` - Text matched against transaction descriptions.
- `bankNames`, `accountIds`, `currencyIsoCodes` - Multi-value fields. Any
  supplied value can match. Blank entries are ignored.
- `minAmount`, `maxAmount` - Inclusive transaction amount range.
- `type` - Transaction type, `DEBIT` or `CREDIT`.

`startDate` and `endDate` are not part of the saved-view API contract. Migration
`V16__delete_legacy_saved_views.sql` deletes saved views persisted with that old
criteria JSON shape.

Example request body:

```json
{
  "name": "December Debits",
  "criteria": {
    "dateFrom": "2024-12-01",
    "dateTo": "2024-12-31",
    "bankNames": ["Capital One"],
    "accountIds": ["checking-12345"],
    "currencyIsoCodes": ["USD"],
    "minAmount": 10.00,
    "maxAmount": 500.00,
    "type": "DEBIT",
    "searchText": "coffee"
  },
  "openEnded": false
}
```

## Open-Ended Views

When `openEnded=true` and `criteria.dateTo` is omitted, the service resolves the
upper date bound to the current date at membership lookup time. When
`criteria.dateTo` is present, that stored value is used.

## Pins And Exclusions

Pins and exclusions are stored as transaction ID sets on the same `saved_view`
row:

- Pinning a transaction adds it to `pinned_ids` and removes it from
  `excluded_ids`.
- Excluding a transaction adds it to `excluded_ids` and removes it from
  `pinned_ids`.
- Deleting a saved view removes its pinned and excluded IDs with the same row.

Pinned and excluded IDs are filtered to active transactions owned by the view
owner before membership is returned.

## Membership Response

`GET /v1/views/{id}/transactions` returns transaction IDs grouped by membership
type:

- `matched` - Active transaction IDs matching the view criteria, excluding
  active excluded IDs.
- `pinned` - Active pinned transaction IDs not already included in `matched`.
- `excluded` - Active excluded transaction IDs.

The saved-view transaction count follows the same effective set:

```text
(matching IDs - active excluded IDs) + active pinned IDs
```

Soft-deleted transactions are ignored in all three membership groups and in the
count.

## Storage

The `saved_view` table stores:

- `criteria` as JSON text using the current `dateFrom` and `dateTo` field names.
- `open_ended` as a boolean.
- `pinned_ids` as JSON text.
- `excluded_ids` as JSON text.

See [Database Schema](database-schema.md#saved_view) for table and index
details.
