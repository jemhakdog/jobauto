let activeConfig = "job_search";
let logEventSource = null;

function showToast(message, isError = false) {
  const toast = document.getElementById("toast");
  toast.innerText = message;
  toast.style.borderColor = isError ? "var(--danger)" : "var(--success)";
  toast.style.display = "block";
  setTimeout(() => {
    toast.style.display = "none";
  }, 3000);
}

// Navigation Tabs
document.querySelectorAll(".tab-btn").forEach(btn => {
  btn.addEventListener("click", () => {
    document.querySelectorAll(".tab-btn").forEach(b => b.classList.remove("active"));
    document.querySelectorAll(".tab-panel").forEach(p => p.classList.remove("active"));
    btn.classList.add("active");
    const panelId = btn.getAttribute("data-tab");
    document.getElementById(panelId).classList.add("active");

    if (panelId === "tab-configs") loadConfig(activeConfig);
    if (panelId === "tab-data") {
      loadUnhandled();
      loadMemory();
      loadTrainingData();
    }
    if (panelId === "tab-applied") {
      loadAppliedJobs();
      loadLlamaLogs();
    }
  });
});

// Status Poller
async function updateStatus() {
  try {
    const res = await fetch("/api/bot/status");
    const data = await res.json();

    const botBadge = document.getElementById("badge-bot");
    const botText = document.getElementById("text-bot");
    if (data.bot_running) {
      botBadge.className = "badge active";
      botText.innerText = "Running (PID: " + data.pid + ")";
      document.getElementById("btn-start").disabled = true;
      document.getElementById("btn-stop").disabled = false;
      connectLogStream();
    } else {
      botBadge.className = "badge inactive";
      botText.innerText = "Stopped";
      document.getElementById("btn-start").disabled = false;
      document.getElementById("btn-stop").disabled = true;
    }

    const llamaBadge = document.getElementById("badge-llama");
    const llamaText = document.getElementById("text-llama");
    llamaBadge.className = data.llama_server_running ? "badge active" : "badge inactive";
    llamaText.innerText = data.llama_server_running ? "Ready" : "Offline";

    const adbBadge = document.getElementById("badge-adb");
    const adbText = document.getElementById("text-adb");
    adbBadge.className = data.adb_connected ? "badge active" : "badge inactive";
    adbText.innerText = data.adb_connected ? "Connected" : "Disconnected";
  } catch (err) {
    console.error("Failed to check status", err);
  }
}

// Start / Stop Bot
document.getElementById("btn-start").addEventListener("click", async () => {
  const adbPort = document.getElementById("adb-port-input").value.trim();
  document.getElementById("terminal").innerText = "[*] Launching bot...\n";
  try {
    const res = await fetch("/api/bot/start", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ adb_port: adbPort || null })
    });
    const data = await res.json();
    if (data.success) {
      showToast("Bot launched!");
      connectLogStream();
      updateStatus();
    } else {
      showToast(data.error || "Failed to start", true);
    }
  } catch (err) {
    showToast("Network error starting bot", true);
  }
});

document.getElementById("btn-stop").addEventListener("click", async () => {
  try {
    const res = await fetch("/api/bot/stop", { method: "POST" });
    const data = await res.json();
    if (data.success) {
      showToast("Bot stopped");
      updateStatus();
    } else {
      showToast(data.error || "Failed to stop", true);
    }
  } catch (err) {
    showToast("Network error stopping bot", true);
  }
});

// Real-time Logs via SSE
function connectLogStream() {
  if (logEventSource) return;
  const terminal = document.getElementById("terminal");
  logEventSource = new EventSource("/api/bot/logs/stream");

  logEventSource.onmessage = (event) => {
    terminal.innerText += event.data + "\n";
    if (document.getElementById("chk-autoscroll").checked) {
      terminal.scrollTop = terminal.scrollHeight;
    }
  };

  logEventSource.addEventListener("end", () => {
    if (logEventSource) {
      logEventSource.close();
      logEventSource = null;
    }
    updateStatus();
  });

  logEventSource.onerror = () => {
    if (logEventSource) {
      logEventSource.close();
      logEventSource = null;
    }
  };
}

document.getElementById("btn-clear-terminal").addEventListener("click", () => {
  document.getElementById("terminal").innerText = "";
});
document.getElementById("btn-refresh-status").addEventListener("click", updateStatus);

// Configs Editor
async function loadConfig(name) {
  activeConfig = name;
  document.getElementById("lbl-active-config").innerText = `Editing: ${name}.json`;
  try {
    const res = await fetch(`/api/config/${name}`);
    const data = await res.json();
    document.getElementById("config-json-editor").value = JSON.stringify(data, null, 2);
  } catch (err) {
    showToast("Failed to load config", true);
  }
}

document.getElementById("btn-save-config").addEventListener("click", async () => {
  const content = document.getElementById("config-json-editor").value;
  let parsed;
  try {
    parsed = JSON.parse(content);
  } catch (err) {
    showToast("Invalid JSON syntax: " + err.message, true);
    return;
  }

  try {
    const res = await fetch(`/api/config/${activeConfig}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(parsed)
    });
    const data = await res.json();
    if (data.success) {
      showToast("Config saved successfully!");
    } else {
      showToast(data.error || "Failed to save config", true);
    }
  } catch (err) {
    showToast("Network error saving config", true);
  }
});

// Unhandled Questions
async function loadUnhandled() {
  try {
    const res = await fetch("/api/data/unhandled");
    const list = await res.json();
    document.getElementById("unhandled-count").innerText = list.length;
    const container = document.getElementById("unhandled-list");
    container.innerHTML = "";

    if (list.length === 0) {
      container.innerHTML = "<p style='color: var(--text-muted); font-size: 0.85rem;'>No unhandled questions! All clear.</p>";
      return;
    }

    list.slice(0, 15).forEach(q => {
      const card = document.createElement("div");
      card.style.background = "var(--bg-input)";
      card.style.padding = "0.75rem";
      card.style.borderRadius = "0.375rem";
      card.style.marginBottom = "0.5rem";

      card.innerHTML = `
        <div style="font-weight: 500; font-size: 0.9rem; margin-bottom: 0.4rem;">${escapeHtml(q)}</div>
        <div style="display: flex; gap: 0.5rem;">
          <input type="text" placeholder="Your answer (e.g. Yes, No, 1 year)..." id="ans-${btoa(encodeURIComponent(q)).replace(/=/g, '')}">
          <button class="btn btn-primary" style="padding: 0.4rem 0.8rem; font-size: 0.8rem;" onclick="saveAnswer('${encodeURIComponent(q)}')">Learn</button>
        </div>
      `;
      container.appendChild(card);
    });
  } catch (err) {
    console.error(err);
  }
}

async function saveAnswer(encodedQ) {
  const q = decodeURIComponent(encodedQ);
  const inputId = `ans-${btoa(encodeURIComponent(q)).replace(/=/g, '')}`;
  const ansInput = document.getElementById(inputId);
  if (!ansInput || !ansInput.value.trim()) {
    showToast("Please enter an answer first", true);
    return;
  }
  const answer = ansInput.value.trim();

  try {
    const res = await fetch("/api/data/memory/learn", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ question: q, answer: answer })
    });
    const data = await res.json();
    if (data.success) {
      showToast("Saved to memory!");
      loadUnhandled();
      loadMemory();
    } else {
      showToast("Failed to save answer", true);
    }
  } catch (err) {
    showToast("Network error", true);
  }
}

// Memory
async function loadMemory(search = "") {
  try {
    const url = search ? `/api/data/memory?search=${encodeURIComponent(search)}` : "/api/data/memory";
    const res = await fetch(url);
    const data = await res.json();
    const tbody = document.getElementById("memory-table-body");
    tbody.innerHTML = "";

    const entries = Object.entries(data);
    if (entries.length === 0) {
      tbody.innerHTML = "<tr><td colspan='2' style='color: var(--text-muted);'>No memory entries found.</td></tr>";
      return;
    }

    entries.slice(0, 50).forEach(([k, v]) => {
      const tr = document.createElement("tr");
      tr.innerHTML = `
        <td style="word-break: break-word;">${escapeHtml(k)}</td>
        <td style="color: var(--accent); font-weight: 500;">${escapeHtml(v)}</td>
      `;
      tbody.appendChild(tr);
    });
  } catch (err) {
    console.error(err);
  }
}

document.getElementById("memory-search").addEventListener("input", (e) => {
  loadMemory(e.target.value.trim());
});

// Training Dataset
async function loadTrainingData() {
  try {
    const res = await fetch("/api/data/training?limit=25");
    const data = await res.json();
    document.getElementById("dataset-count").innerText = data.total;
    const container = document.getElementById("training-list");
    container.innerHTML = "";

    data.items.forEach((item, idx) => {
      const div = document.createElement("div");
      div.style.borderBottom = "1px solid var(--border)";
      div.style.padding = "0.5rem 0";
      div.innerHTML = `
        <div><strong style="color: var(--text-muted);">Prompt:</strong> ${escapeHtml(item.prompt || item.raw || '')}</div>
        <div><strong style="color: var(--accent);">Completion:</strong> ${escapeHtml(item.completion || '')}</div>
      `;
      container.appendChild(div);
    });
  } catch (err) {
    console.error(err);
  }
}

// Applied History
async function loadAppliedJobs() {
  try {
    const res = await fetch("/api/data/applied");
    const jobs = await res.json();
    const tbody = document.getElementById("applied-table-body");
    tbody.innerHTML = "";

    if (jobs.length === 0) {
      tbody.innerHTML = "<tr><td colspan='4' style='color: var(--text-muted);'>No application records found.</td></tr>";
      return;
    }

    jobs.forEach(job => {
      const tr = document.createElement("tr");
      tr.innerHTML = `
        <td>${escapeHtml(job.timestamp || job.date || '')}</td>
        <td style="font-weight: 500;">${escapeHtml(job.job_title || job.title || '')}</td>
        <td>${escapeHtml(job.company || '')}</td>
        <td><span class="badge active">${escapeHtml(job.status || 'Applied')}</span></td>
      `;
      tbody.appendChild(tr);
    });
  } catch (err) {
    console.error(err);
  }
}

// LLaMA Logs
async function loadLlamaLogs() {
  try {
    const res = await fetch("/api/data/llama-logs");
    const data = await res.json();
    document.getElementById("llama-logs").innerText = data.logs;
  } catch (err) {
    console.error(err);
  }
}

function escapeHtml(text) {
  if (text === null || text === undefined) return "";
  return String(text)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

// Initial Boot
updateStatus();
setInterval(updateStatus, 3000);
loadConfig("job_search");
