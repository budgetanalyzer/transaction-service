#!/bin/bash

set -euo pipefail

# Script to generate BKK Bank CSV files with random transactions
# Usage: ./gen-bkk-bank-csv.sh [--clean] START_DATE END_DATE
# Date format: YYYY-MM-DD

readonly SCRIPT_NAME=$(basename "$0")

# Transaction descriptions for debits
readonly DEBIT_DESCRIPTIONS=(
    "Payment for Goods /Services"
    "PromptPay Transfer/Top Up eWallet"
    "Interbank Transfer"
    "ATM Withdrawal"
    "Bill Payment"
    "Cash Withdrawal"
    "Online Payment"
    "Transfer to Savings"
    "Loan Payment"
    "Service Fee"
    "International Transfer"
    "Insurance Payment"
)

# Transaction descriptions for credits
readonly CREDIT_DESCRIPTIONS=(
    "Salary Credit"
    "Interbank Transfer"
    "Foreign Remittance"
    "PromptPay Receive"
    "Cash Deposit"
    "Refund"
    "Interest Credit"
    "Transfer from Savings"
    "Reimbursement"
)

# Channel types
readonly CHANNELS=(
    "MOB"
    "ATM"
    "BRN"
    "INT"
    "AUTO"
)

usage() {
    cat << EOF
Usage: $SCRIPT_NAME [--clean] START_DATE END_DATE

Generate a BKK Bank CSV file with random transactions.

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

epoch_to_bkk_format() {
    # Format: "31 Oct 2025 22:42"
    date -d "@$1" +"%-d %b %Y %H:%M"
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

format_amount() {
    local amount="$1"
    # Format number with comma thousands separator
    # First format to 2 decimal places, then add commas
    local formatted=$(printf "%.2f" "$amount")
    # Add comma separators for thousands
    echo "$formatted" | sed ':a;s/\B[0-9]\{3\}\>/,&/;ta'
}

random_balance() {
    # Generate a random balance between 10000 and 200000
    echo "$(awk 'BEGIN{srand(); printf "%.2f\n", 10000 + rand() * 190000}')"
}

get_random_description() {
    local type="$1"
    if [[ "$type" == "debit" ]]; then
        local idx=$((RANDOM % ${#DEBIT_DESCRIPTIONS[@]}))
        echo "${DEBIT_DESCRIPTIONS[$idx]}"
    else
        local idx=$((RANDOM % ${#CREDIT_DESCRIPTIONS[@]}))
        echo "${CREDIT_DESCRIPTIONS[$idx]}"
    fi
}

get_random_channel() {
    local idx=$((RANDOM % ${#CHANNELS[@]}))
    echo "${CHANNELS[$idx]}"
}

generate_transactions() {
    local start_epoch="$1"
    local end_epoch="$2"
    local use_clean="$3"
    local date_range=$((end_epoch - start_epoch))
    local num_days=$((date_range / 86400))
    
    # Generate 1-2 transactions per day on average
    local avg_days_between=1
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
        local transaction_date=$(epoch_to_bkk_format "$transaction_epoch")
        
        # 70% chance of debit, 30% chance of credit
        local transaction_type="debit"
        if [[ $((RANDOM % 10)) -lt 3 ]]; then
            transaction_type="credit"
        fi
        
        local description=$(get_random_description "$transaction_type")
        local channel=$(get_random_channel)
        
        # Generate appropriate amounts based on transaction type
        local amount
        if [[ "$use_clean" == "true" ]]; then
            # Clean amounts: 1, 10, 100, or 1000
            amount=$(random_clean_amount)
        elif [[ "$transaction_type" == "debit" ]]; then
            # Debits: 10 - 5000
            amount=$(random_amount 10 5000)
        else
            # Credits: 500 - 100000
            amount=$(random_amount 500 100000)
        fi
        
        local formatted_amount=$(format_amount "$amount")
        
        # Calculate new balance
        if [[ "$transaction_type" == "debit" ]]; then
            balance=$(awk -v bal="$balance" -v amt="$amount" 'BEGIN{printf "%.2f", bal - amt}')
        else
            balance=$(awk -v bal="$balance" -v amt="$amount" 'BEGIN{printf "%.2f", bal + amt}')
        fi
        
        local formatted_balance=$(format_amount "$balance")
        
        # Build CSV line based on transaction type
        local debit_col=""
        local credit_col=""
        
        if [[ "$transaction_type" == "debit" ]]; then
            debit_col="\"$formatted_amount\""
        else
            credit_col="\"$formatted_amount\""
        fi
        
        # Store as CSV line with epoch for sorting
        # Format: ,Date,Description,Debit,Credit,Balance,Channel,
        transactions+=("$transaction_epoch|\" \",\"$transaction_date\",\"$description\",$debit_col,$credit_col,\"$formatted_balance\",\"$channel\",")
    done
    
    # Sort transactions by date (epoch timestamp) in reverse order (newest first)
    printf '%s\n' "${transactions[@]}" | sort -t'|' -k1 -rn | cut -d'|' -f2
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
    local output_file="bkk-bank-${start_date}-to-${end_date}${suffix}.csv"

    # Generate CSV
    local clean_msg=""
    if [[ "$use_clean" == "true" ]]; then
        clean_msg=" (with clean amounts)"
    fi
    echo "Generating transactions from $start_date to $end_date${clean_msg}..."

    {
        # Header row
        echo ",Date,Description,Debit,Credit,Balance,Channel,"

        # Generate and output transactions
        generate_transactions "$start_epoch" "$end_epoch" "$use_clean"
    } > "$output_file"
    
    local num_transactions=$(($(wc -l < "$output_file") - 1))
    echo "Generated $num_transactions transactions in $output_file"
}

main "$@"
