#!/bin/bash

set -euo pipefail

# Script to generate Capital One CSV files with random transactions
# Usage: ./gen-capital-one-csv.sh [--clean] START_DATE END_DATE
# Date format: YYYY-MM-DD

readonly SCRIPT_NAME=$(basename "$0")
readonly ACCOUNT_NUMBER="0568"

# Transaction descriptions
readonly DEBIT_DESCRIPTIONS=(
    "ATM Withdrawal"
    "Debit Card Purchase"
    "Online Payment"
    "Check Payment"
    "Phone Service"
    "Electric Bill"
    "Gas Bill"
    "Internet Service"
    "Grocery Store"
    "Restaurant"
    "Gas Station"
    "Insurance Payment"
    "Mortgage Payment"
    "Rent Payment"
    "Credit Card Payment"
    "Medical Payment"
    "Pharmacy"
    "Car Payment"
    "Streaming Service"
    "Gym Membership"
)

readonly CREDIT_DESCRIPTIONS=(
    "Direct Deposit"
    "Mobile Deposit"
    "ATM Deposit"
    "Wire Transfer"
    "Payroll Deposit"
    "Interest Paid"
    "Refund"
    "Transfer from Savings"
    "Reimbursement"
)

usage() {
    cat << EOF
Usage: $SCRIPT_NAME [--clean] START_DATE END_DATE

Generate a Capital One CSV file with random transactions.

Arguments:
    --clean       Optional flag to use clean amounts (1, 10, 100, or 1000) for easier currency conversion
    START_DATE    Start date in YYYY-MM-DD format
    END_DATE      End date in YYYY-MM-DD format

Examples:
    $SCRIPT_NAME 2000-05-02 2020-01-01
    $SCRIPT_NAME --clean 2000-05-02 2020-01-01

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

epoch_to_mmddyy() {
    date -d "@$1" +%m/%d/%y
}

random_amount() {
    local min=1
    local max=2000
    # Generate random amount with 2 decimal places
    echo "$(awk -v min=$min -v max=$max 'BEGIN{srand(); printf "%.2f\n", min + rand() * (max - min)}')"
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
    if [[ "$type" == "Debit" ]]; then
        local idx=$((RANDOM % ${#DEBIT_DESCRIPTIONS[@]}))
        echo "${DEBIT_DESCRIPTIONS[$idx]}"
    else
        local idx=$((RANDOM % ${#CREDIT_DESCRIPTIONS[@]}))
        echo "${CREDIT_DESCRIPTIONS[$idx]}"
    fi
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
    
    # Generate random transactions
    declare -a transactions=()
    for ((i=0; i<num_transactions; i++)); do
        # Random timestamp within range using awk for better randomness
        local random_offset=$(awk -v range=$date_range 'BEGIN{srand(); print int(rand() * range)}')
        local transaction_epoch=$((start_epoch + random_offset))
        local transaction_date=$(epoch_to_mmddyy "$transaction_epoch")
        
        # 70% chance of debit, 30% chance of credit
        local transaction_type="Debit"
        if [[ $((RANDOM % 10)) -lt 3 ]]; then
            transaction_type="Credit"
        fi
        
        local description=$(get_random_description "$transaction_type")
        local amount
        if [[ "$use_clean" == "true" ]]; then
            # Clean amounts: 1, 10, 100, or 1000
            amount=$(random_clean_amount)
        else
            amount=$(random_amount)
        fi
        local balance=$(random_balance)
        
        # Store as CSV line with epoch for sorting
        transactions+=("$transaction_epoch|$ACCOUNT_NUMBER,$description,$transaction_date,$transaction_type,$amount,$balance")
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
    local output_file="capital-one-${start_date}-to-${end_date}${suffix}.csv"

    # Generate CSV
    local clean_msg=""
    if [[ "$use_clean" == "true" ]]; then
        clean_msg=" (with clean amounts)"
    fi
    echo "Generating transactions from $start_date to $end_date${clean_msg}..."

    {
        # Header row
        echo "Account Number,Transaction Description,Transaction Date,Transaction Type,Transaction Amount,Balance"

        # Generate and output transactions
        generate_transactions "$start_epoch" "$end_epoch" "$use_clean"
    } > "$output_file"
    
    local num_transactions=$(($(wc -l < "$output_file") - 2))
    echo "Generated $num_transactions transactions in $output_file"
}

main "$@"
