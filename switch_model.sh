#!/bin/bash

TARGET="$1"

case "$TARGET" in
    qwen|"")
        MODEL="Qwen2.5-0.5B-Instruct.Q8_0.gguf"
        ;;
    lfm|lfm2.6b)
        MODEL="LFM2.5-2.6B-Q8_0.gguf"
        ;;
    lfm-small|lfm230m)
        MODEL="LFM2.5-230M-BF16.gguf"
        ;;
    *)
        echo "Usage: ./switch_model.sh [qwen | lfm | lfm-small]"
        exit 1
        ;;
esac

sed -i "s/^MODEL_PATH=.*/MODEL_PATH=\"$MODEL\"/" start.sh
echo "[✓] Default model switched to: $MODEL"
