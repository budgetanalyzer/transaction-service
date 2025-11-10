# Transaction Service - Domain Model

**Status:** Active
**Service:** transaction-service

## Overview

This document describes the business domain entities and their relationships in the transaction service.

## Core Entities

### Transaction

**Purpose:** Represents a single financial transaction

**Key Attributes:**
- `id` (UUID) - Unique identifier
- `accountId` (UUID) - Reference to account
- `amount` (BigDecimal) - Transaction amount
- `currency` (String) - Currency code (USD, THB, etc.)
- `transactionDate` (LocalDate) - When transaction occurred
- `description` (String) - Transaction description
- `category` (String) - Optional categorization
- `type` (Enum) - DEBIT or CREDIT

**Business Rules:**
- Amount must be positive
- Currency must be valid ISO code
- Transaction date cannot be in future
- Description required for all transactions

**Discovery:**
```bash
# Find Transaction entity
grep -r "class Transaction" src/main/java
```

### Budget

**Purpose:** Represents a budget allocation

**Key Attributes:**
- `id` (UUID) - Unique identifier
- `name` (String) - Budget name
- `amount` (BigDecimal) - Budget amount
- `currency` (String) - Currency code
- `startDate` (LocalDate) - Budget period start
- `endDate` (LocalDate) - Budget period end
- `category` (String) - Budget category

**Business Rules:**
- Start date must be before end date
- Amount must be positive
- Period must not overlap with existing budgets in same category

**Discovery:**
```bash
# Find Budget entity
grep -r "class Budget" src/main/java
```

### Category

**Purpose:** Hierarchical categorization for transactions and budgets

**Key Attributes:**
- `id` (UUID) - Unique identifier
- `name` (String) - Category name
- `parentId` (UUID) - Optional parent category
- `type` (Enum) - INCOME or EXPENSE

**Business Rules:**
- Category names must be unique within parent
- Circular references not allowed
- Maximum 3 levels of nesting

**Discovery:**
```bash
# Find Category entity
grep -r "class Category" src/main/java
```

## Domain Relationships

```
Budget 1 ──→ * Transaction (via category)
Category 1 ──→ * Transaction
Category 1 ──→ * Budget
Category 0..1 ──→ * Category (hierarchical)
Account 1 ──→ * Transaction
```

## Aggregates

### Transaction Aggregate
**Root:** Transaction
**Entities:** Transaction only (no child entities currently)
**Value Objects:** Money (amount + currency), TransactionType

### Budget Aggregate
**Root:** Budget
**Entities:** Budget only
**Value Objects:** DateRange (startDate + endDate), Money

## Value Objects

### Money
**Components:**
- `amount` (BigDecimal)
- `currency` (String)

**Business Rules:**
- Currency must be valid ISO 4217 code
- Amount precision: 2 decimal places
- Immutable

### DateRange
**Components:**
- `startDate` (LocalDate)
- `endDate` (LocalDate)

**Business Rules:**
- End date must be after start date
- No null dates allowed
- Immutable

## Domain Events (Future)

### TransactionCreated
- Published when new transaction created
- Contains: transactionId, accountId, amount, currency, date

### BudgetExceeded
- Published when spending exceeds budget
- Contains: budgetId, category, exceeded amount, date

### CategoryChanged
- Published when transaction category updated
- Contains: transactionId, old category, new category

## Discovery Commands

```bash
# Find all entities
find src/main/java -name "*Entity*.java" -o -name "*entity*"

# Find repositories
grep -r "@Repository" src/

# Find domain services
grep -r "@Service" src/ | grep -i domain

# View database schema
cat src/main/resources/db/migration/V*.sql
```

## Database Mapping

See: [database-schema.md](database-schema.md) for complete schema details.

**JPA Strategy:** Pure JPA (Jakarta Persistence API), no Hibernate-specific features
- See: [@service-common/docs/spring-boot-conventions.md](../../service-common/docs/spring-boot-conventions.md#persistence-layer-pure-jpa)

## References

- **Spring Boot Patterns:** [@service-common/docs/spring-boot-conventions.md](../../service-common/docs/spring-boot-conventions.md)
- **Database Schema:** [database-schema.md](database-schema.md)
- **API Spec:** [api/README.md](api/README.md)
