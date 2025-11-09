#!/bin/bash

set -euo pipefail

# Script to generate Truist CSV files with random transactions
# Usage: ./gen-truist-csv.sh [--clean] START_DATE END_DATE
# Date format: YYYY-MM-DD

readonly SCRIPT_NAME=$(basename "$0")

# Transaction types
readonly TRANSACTION_TYPES=(
    "Deposit"
    "Withdrawal"
)

# Transaction descriptions for credits/deposits
readonly CREDIT_DESCRIPTIONS=(
    "ONLINE FROM ****5475 - TRUIST ONLINE TRANSFER"
    "DIRECT DEPOSIT - PAYROLL"
    "MOBILE DEPOSIT"
    "ATM DEPOSIT"
    "WIRE TRANSFER IN"
    "ZELLE FROM"
    "ACH CREDIT"
    "REFUND"
    "INTEREST PAYMENT"
    "REIMBURSEMENT"
)

# Transaction descriptions for debits/withdrawals
readonly DEBIT_DESCRIPTIONS=(
    "DEBIT CARD PURCHASE"
    "ATM WITHDRAWAL"
    "ONLINE PAYMENT"
    "CHECK #"
    "BILL PAY"
    "WIRE TRANSFER OUT"
    "ZELLE TO"
    "ACH DEBIT"
    "SERVICE FEE"
    "MONTHLY MAINTENANCE FEE"
    "OVERDRAFT FEE"
)

usage() {
    cat << EOF
Usage: $SCRIPT_NAME [--clean] START_DATE END_DATE

Generate a Truist CSV file with random transactions.

Arguments:
    --clean       Optional flag to use clean amounts (1, 10, 100, or 1000) for easier currency conversion
    START_DATE    Start date in YYYY-MM-DD format
    END_DATE      End date in YYYY-MM-DD format

Examples:
    $SCRIPT_NAME 2024-01-01 2024-12-31
    $SCRIPT_NAME --clean 2024-01-01 2024-12-31

EOF
    exit 1
}

error_exit() {
    echo "Error: $1" >&2
    exit 1
}

validate_date() {
    local date_str="$1"
    if ! date -d "$date_str" &>/dev/null; then
        error_exit "Invalid date format: $date_str. Expected YYYY-MM-DD"
    fi
}

date_to_epoch() {
    date -d "$1" +%s
}

epoch_to_mmddyyyy() {
    date -d "@$1" +%m/%d/%Y
}

random_amount() {
    local min="$1"
    local max="$2"
    # Generate random amount with 2 decimal places
    echo "$(awk -v min="$min" -v max="$max" 'BEGIN{srand(); printf "%.2f\n", min + rand() * (max - min)}')"
}

random_clean_amount() {
    # Return one of: 1, 10, 100, or 1000 randomly
    local amounts=(1 10 100 1000)
    local idx=$((RANDOM % ${#amounts[@]}))
    echo "${amounts[$idx]}.00"
}

random_balance() {
    # Generate a random balance between 100 and 10000
    echo "$(awk 'BEGIN{srand(); printf "%.2f\n", 100 + rand() * 9900}')"
}

get_random_description() {
    local type="$1"
    local check_num="$2"
    
    if [[ "$type" == "Deposit" ]]; then
        local idx=$((RANDOM % ${#CREDIT_DESCRIPTIONS[@]}))
        echo "${CREDIT_DESCRIPTIONS[$idx]}"
    else
        # Withdrawal type
        if [[ -n "$check_num" ]]; then
            echo "CHECK #$check_num"
        else
            local idx=$((RANDOM % ${#DEBIT_DESCRIPTIONS[@]}))
            echo "${DEBIT_DESCRIPTIONS[$idx]}"
        fi
    fi
}

generate_check_number() {
    # Generate random check number between 1000 and 9999
    echo $((1000 + RANDOM % 9000))
}

generate_transactions() {
    local start_epoch="$1"
    local end_epoch="$2"
    local use_clean="$3"
    local date_range=$((end_epoch - start_epoch))
    local num_days=$((date_range / 86400))
    
    # Generate 1 transaction per 7-10 days on average
    local avg_days_between=8
    local num_transactions=$((num_days / avg_days_between))
    
    # Ensure at least a few transactions
    if [[ $num_transactions -lt 5 ]]; then
        num_transactions=5
    fi
    
    # Starting balance
    local balance=$(random_balance)
    
    # Generate random transactions
    declare -a transactions=()
    for ((i=0; i<num_transactions; i++)); do
        # Random timestamp within range using awk for better randomness
        local random_offset=$(awk -v range=$date_range 'BEGIN{srand(); print int(rand() * range)}')
        local transaction_epoch=$((start_epoch + random_offset))
        local transaction_date=$(epoch_to_mmddyyyy "$transaction_epoch")
        
        # Posted date is same as transaction date in most cases
        # 10% chance posted date is 1-2 days after transaction date
        local posted_date="$transaction_date"
        if [[ $((RANDOM % 10)) -lt 1 ]]; then
            local days_later=$((1 + RANDOM % 2))
            local posted_epoch=$((transaction_epoch + days_later * 86400))
            posted_date=$(epoch_to_mmddyyyy "$posted_epoch")
        fi
        
        # Select transaction type
        # 40% Deposit, 60% Withdrawal
        local rand=$((RANDOM % 100))
        local transaction_type
        if [[ $rand -lt 40 ]]; then
            transaction_type="Deposit"
        else
            transaction_type="Withdrawal"
        fi
        
        # Generate check number if applicable (only for some withdrawals)
        local check_num=""
        if [[ "$transaction_type" == "Withdrawal" && $((RANDOM % 10)) -lt 2 ]]; then
            check_num=$(generate_check_number)
        fi
        
        local description=$(get_random_description "$transaction_type" "$check_num")
        
        # Generate amount based on transaction type
        local amount
        local is_credit=false
        if [[ "$use_clean" == "true" ]]; then
            # Clean amounts: 1, 10, 100, or 1000
            amount=$(random_clean_amount)
            if [[ "$transaction_type" == "Deposit" ]]; then
                is_credit=true
            else
                is_credit=false
            fi
        elif [[ "$transaction_type" == "Deposit" ]]; then
            # Deposits: 50 - 5000
            amount=$(random_amount 50 5000)
            is_credit=true
        else
            # Withdrawals: 10 - 2000
            amount=$(random_amount 10 2000)
            is_credit=false
        fi
        
        # Calculate new balance
        if [[ "$is_credit" == true ]]; then
            balance=$(awk -v bal="$balance" -v amt="$amount" 'BEGIN{printf "%.2f", bal + amt}')
        else
            balance=$(awk -v bal="$balance" -v amt="$amount" 'BEGIN{printf "%.2f", bal - amt}')
        fi
        
        # Format amount without currency symbols (just the number)
        local formatted_amount="$amount"
        local formatted_balance="$balance"
        
        # Store as CSV line with epoch for sorting
        # Format: Posted Date,Transaction Date,Transaction Type,Check/Serial #,Description,Amount,Daily Posted Balance
        transactions+=("$transaction_epoch|$posted_date,$transaction_date,$transaction_type,$check_num,$description,$formatted_amount,$formatted_balance")
    done
    
    # Sort transactions by date (epoch timestamp)
    printf '%s\n' "${transactions[@]}" | sort -t'|' -k1 -n | cut -d'|' -f2
}

main() {
    # Parse optional --clean flag
    local use_clean="false"
    if [[ $# -ge 1 && "$1" == "--clean" ]]; then
        use_clean="true"
        shift
    fi

    # Validate arguments
    if [[ $# -ne 2 ]]; then
        usage
    fi

    local start_date="$1"
    local end_date="$2"
    
    # Validate date formats
    validate_date "$start_date"
    validate_date "$end_date"
    
    # Convert to epoch timestamps
    local start_epoch=$(date_to_epoch "$start_date")
    local end_epoch=$(date_to_epoch "$end_date")
    
    # Validate date range
    if [[ $start_epoch -ge $end_epoch ]]; then
        error_exit "Start date must be before end date"
    fi
    
    # Generate output filename
    local suffix=""
    if [[ "$use_clean" == "true" ]]; then
        suffix="-clean"
    fi
    local output_file="truist-${start_date}-to-${end_date}${suffix}.csv"

    # Generate CSV
    local clean_msg=""
    if [[ "$use_clean" == "true" ]]; then
        clean_msg=" (with clean amounts)"
    fi
    echo "Generating transactions from $start_date to $end_date${clean_msg}..."

    {
        # Header row
        echo "Posted Date,Transaction Date,Transaction Type,Check/Serial #,Description,Amount,Daily Posted Balance"

        # Generate and output transactions
        generate_transactions "$start_epoch" "$end_epoch" "$use_clean"
    } > "$output_file"
    
    local num_transactions=$(($(wc -l < "$output_file") - 1))
    echo "Generated $num_transactions transactions in $output_file"
}

main "$@"
