#!/bin/bash

API_KEY="${GROQ_API_KEY}"
LOG="/Users/mac/IdeaProjects/APL/groq_limits.log"

echo "=== Groq rate limit monitor started: $(date) ===" | tee -a "$LOG"
echo "Checking every 5 minutes for 1 hour (12 iterations)" | tee -a "$LOG"
echo "" | tee -a "$LOG"

for i in $(seq 1 12); do
    TIMESTAMP=$(date '+%H:%M:%S')

    RESPONSE=$(curl -si https://api.groq.com/openai/v1/chat/completions \
        -H "Authorization: Bearer $API_KEY" \
        -H "Content-Type: application/json" \
        -d '{"model":"llama-3.3-70b-versatile","messages":[{"role":"user","content":"1+1=?"}],"max_tokens":5}' \
        2>/dev/null)

    LIMIT_REQ=$(echo "$RESPONSE"    | grep -i "x-ratelimit-limit-requests:"     | tr -d '\r' | awk '{print $2}')
    REMAIN_REQ=$(echo "$RESPONSE"   | grep -i "x-ratelimit-remaining-requests:" | tr -d '\r' | awk '{print $2}')
    RESET_REQ=$(echo "$RESPONSE"    | grep -i "x-ratelimit-reset-requests:"     | tr -d '\r' | awk '{print $2}')
    LIMIT_TOKENS=$(echo "$RESPONSE" | grep -i "x-ratelimit-limit-tokens:"       | tr -d '\r' | awk '{print $2}')
    REMAIN_TOKENS=$(echo "$RESPONSE"| grep -i "x-ratelimit-remaining-tokens:"   | tr -d '\r' | awk '{print $2}')
    RESET_TOKENS=$(echo "$RESPONSE" | grep -i "x-ratelimit-reset-tokens:"       | tr -d '\r' | awk '{print $2}')
    HTTP_CODE=$(echo "$RESPONSE"    | head -1 | awk '{print $2}')

    LINE="[$TIMESTAMP] #$i | HTTP=$HTTP_CODE | requests: $REMAIN_REQ/$LIMIT_REQ (reset: $RESET_REQ) | tokens: $REMAIN_TOKENS/$LIMIT_TOKENS (reset: $RESET_TOKENS)"
    echo "$LINE" | tee -a "$LOG"

    if [ $i -lt 12 ]; then
        sleep 300
    fi
done

echo "" | tee -a "$LOG"
echo "=== Monitor finished: $(date) ===" | tee -a "$LOG"
