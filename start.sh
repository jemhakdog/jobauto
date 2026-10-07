#!/bin/bash

MODEL_PATH="Qwen2.5-0.5B-Instruct.Q8_0.gguf"
RERANKER_PATH="bge-reranker-base-q8_0.gguf"
SERVER_BIN="../llama.cpp/build/bin/llama-server"

echo "=================================================="
echo "    Jobstreet Auto-Applier All-In-One Launcher    "
echo "=================================================="

# 1. Check and Start Operations LLM (port 8080)
if curl -s http://127.0.0.1:8080/health | grep -q "ok"; then
    echo "[✓] Operations model (Qwen) is already running on port 8080."
else
    echo "[*] Starting operations model on port 8080 in background..."
    nohup $SERVER_BIN -m $MODEL_PATH --host 127.0.0.1 --port 8080 -c 1024 -t 4 > llama_server.log 2>&1 &
    
    echo -n "[*] Waiting for operations model to initialize..."
    for i in {1..15}; do
        if curl -s http://127.0.0.1:8080/health | grep -q "ok"; then
            echo " Ready!"
            break
        fi
        sleep 1
        echo -n "."
    done
fi

# 2. Check and Start Reranker Model (port 8081)
if curl -s http://127.0.0.1:8081/health | grep -q "ok"; then
    echo "[✓] Reranker model (BGE) is already running on port 8081."
elif [ -f "$RERANKER_PATH" ]; then
    echo "[*] Starting dedicated Reranker model on port 8081..."
    nohup $SERVER_BIN -m $RERANKER_PATH --host 127.0.0.1 --port 8081 --reranking -c 512 -t 2 > reranker.log 2>&1 &
    
    echo -n "[*] Waiting for reranker to initialize..."
    for i in {1..15}; do
        if curl -s http://127.0.0.1:8081/health | grep -q "ok"; then
            echo " Ready!"
            break
        fi
        sleep 1
        echo -n "."
    done
fi

# 2. Check and Connect ADB
if adb devices | grep -q "device$"; then
    echo "[✓] ADB is connected."
else
    echo "[!] ADB is not connected to your phone."
    read -p "Enter current Wireless Debugging Port from Settings: " PORT
    adb connect 127.0.0.1:$PORT
    
    if ! adb devices | grep -q "device$"; then
        echo "[-] Failed to connect to 127.0.0.1:$PORT. Trying LAN IP..."
        adb connect 192.168.1.39:$PORT
    fi
fi

# 3. Verify ADB connection before starting bot
if ! adb devices | grep -q "device$"; then
    echo "[-] Error: Device still not connected. Please check Wireless Debugging."
    exit 1
fi

echo "[+] Everything is ready. Starting Jobstreet Bot..."
sleep 1
python jobstreet_bot.py
