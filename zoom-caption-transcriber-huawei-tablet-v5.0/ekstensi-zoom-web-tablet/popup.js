// Popup Script v4.0 — Auto-Inject (All Frames) & Item Caption Monitor
document.addEventListener('DOMContentLoaded', () => {
  let transcript = [];
  let isRecording = true;
  let liveMonitor = null;

  const feed = document.getElementById('transcript-feed');
  const statSpeakers = document.getElementById('stat-speakers');
  const statTurns = document.getElementById('stat-turns');
  const statWords = document.getElementById('stat-words');
  const statusDot = document.getElementById('status-dot');
  const statusText = document.getElementById('status-text');
  const inputSearch = document.getElementById('input-search');
  const selectSpeaker = document.getElementById('select-speaker');
  const btnToggleRecord = document.getElementById('btn-toggle-record');
  const btnClear = document.getElementById('btn-clear');
  const btnCopy = document.getElementById('btn-copy');
  const btnExportMd = document.getElementById('btn-export-md');
  const btnExportTxt = document.getElementById('btn-export-txt');
  const meetingTitle = document.getElementById('meeting-title');

  // Live Monitor Elements
  const lmStatusTitle = document.getElementById('lm-status-title');
  const lmSourceBadge = document.getElementById('lm-source-badge');
  const lmAvatar = document.getElementById('lm-avatar');
  const lmSpeaker = document.getElementById('lm-speaker');
  const lmTime = document.getElementById('lm-time');
  const lmText = document.getElementById('lm-text');
  const btnPickTarget = document.getElementById('btn-pick-target');
  const btnRescan = document.getElementById('btn-rescan');

  const speakerColors = {};
  const palette = ['#6366f1', '#ec4899', '#10b981', '#f59e0b', '#06b6d4', '#8b5cf6', '#ef4444'];
  let colorIdx = 0;

  function getSpeakerColor(speaker, fallbackBg) {
    if (fallbackBg && fallbackBg !== 'rgba(0, 0, 0, 0)' && fallbackBg !== 'transparent') {
      return fallbackBg;
    }
    if (!speakerColors[speaker]) {
      speakerColors[speaker] = palette[colorIdx++ % palette.length];
    }
    return speakerColors[speaker];
  }

  function getInitials(name) {
    const clean = (name || '').trim();
    if (!clean) return 'P';
    if (clean.length <= 3 && /^[A-Z0-9]+$/i.test(clean)) return clean.toUpperCase();
    const parts = clean.split(/\s+/).filter(Boolean);
    if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
    return clean.slice(0, 2).toUpperCase();
  }

  function escapeHtml(str) {
    if (!str) return '';
    return str
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  /**
   * AUTO-INJECT ke seluruh frame (termasuk #webclient iframe) pada tab Zoom aktif!
   */
  async function ensureInjectedAndScan() {
    try {
      const tabs = await chrome.tabs.query({ active: true, currentWindow: true });
      const activeTab = tabs[0];
      if (!activeTab || !activeTab.id) return;

      if (activeTab.url && activeTab.url.includes('zoom.us')) {
        try {
          await chrome.scripting.insertCSS({
            target: { tabId: activeTab.id, allFrames: true },
            files: ['content.css']
          });
          await chrome.scripting.executeScript({
            target: { tabId: activeTab.id, allFrames: true },
            files: ['content.js']
          });
        } catch (err) {
          // Lanjutkan jika sudah ter-inject
        }
        chrome.storage.local.set({ forceScanTrigger: Date.now() });
        chrome.tabs.sendMessage(activeTab.id, { action: 'forceScan' }, () => {
          if (chrome.runtime.lastError) {}
        });
      }
    } catch (e) {
      console.warn('Auto-inject warning:', e);
    }
  }

  function loadData() {
    chrome.storage.local.get(['zoomTranscript', 'isRecording', 'meetingTitle', 'liveMonitorState'], (res) => {
      if (Array.isArray(res.zoomTranscript)) {
        transcript = res.zoomTranscript;
      }
      if (typeof res.isRecording === 'boolean') {
        isRecording = res.isRecording;
      }
      if (res.meetingTitle) {
        meetingTitle.textContent = res.meetingTitle;
      }
      if (res.liveMonitorState) {
        liveMonitor = res.liveMonitorState;
      }
      updateUI();
    });
  }

  function updateUI() {
    updateStatusBadge();
    updateLiveMonitorUI();
    updateStats();
    updateSpeakerDropdown();
    renderFeed();
  }

  function renderAvatarNode(el, item) {
    if (!el || !item) return;
    if (item.avatarUrl) {
      el.innerHTML = `<img src="${escapeHtml(item.avatarUrl)}" alt="${escapeHtml(item.speaker)}" />`;
      el.style.background = 'transparent';
    } else {
      el.textContent = item.initials || getInitials(item.speaker);
      el.style.background = getSpeakerColor(item.speaker, item.avatarBg);
    }
  }

  function updateLiveMonitorUI() {
    if (liveMonitor && liveMonitor.rawText) {
      lmStatusTitle.textContent = '🟢 ITEM CAPTION AKTIF TERDETEKSI';
      lmStatusTitle.style.color = '#34d399';
      lmSourceBadge.textContent = liveMonitor.sourceType || 'Otomatis';
      renderAvatarNode(lmAvatar, liveMonitor);
      lmSpeaker.textContent = liveMonitor.speaker;
      lmTime.textContent = liveMonitor.timestamp || '';
      lmText.textContent = liveMonitor.rawText;
    } else if (transcript.length > 0) {
      const last = transcript[transcript.length - 1];
      lmStatusTitle.textContent = '🟢 ITEM CAPTION TERAKHIR';
      lmStatusTitle.style.color = '#34d399';
      lmSourceBadge.textContent = 'Tersimpan';
      renderAvatarNode(lmAvatar, last);
      lmSpeaker.textContent = last.speaker;
      lmTime.textContent = last.timestamp || '';
      lmText.textContent = last.text;
    } else {
      lmStatusTitle.textContent = '⚡ MEMINDAI TEMPAT CAPTION OTOMATIS...';
      lmStatusTitle.style.color = '#818cf8';
      lmSourceBadge.textContent = 'Auto-Detect';
      lmAvatar.textContent = '🎙️';
      lmAvatar.style.background = 'linear-gradient(135deg, #6366f1, #8b5cf6)';
      lmSpeaker.textContent = 'Menunggu Item Caption Muncul...';
      lmTime.textContent = '--:--';
      lmText.textContent =
        'Saat kotak caption Zoom aktif di layar, setiap item (foto/inisial + kalimat) otomatis tertangkap tanpa perlu klik manual.';
    }
  }

  function updateStatusBadge() {
    if (isRecording) {
      statusDot.style.backgroundColor = '#10b981';
      statusDot.style.boxShadow = '0 0 8px #10b981';
      statusDot.classList.add('pulse');
      statusText.textContent = 'Merekam';
      btnToggleRecord.textContent = '⏸️ Jeda Transkrip';
    } else {
      statusDot.style.backgroundColor = '#94a3b8';
      statusDot.style.boxShadow = 'none';
      statusDot.classList.remove('pulse');
      statusText.textContent = 'Dijeda';
      btnToggleRecord.textContent = '▶️ Lanjutkan Transkrip';
    }
  }

  function updateStats() {
    const uniqueSpeakers = new Set(transcript.map((t) => t.speaker));
    statSpeakers.textContent = uniqueSpeakers.size;
    statTurns.textContent = transcript.length;

    let totalWords = 0;
    transcript.forEach((t) => {
      const words = (t.text || '').trim().split(/\s+/).filter(Boolean);
      totalWords += words.length;
    });
    statWords.textContent = totalWords;
  }

  function updateSpeakerDropdown() {
    const currentVal = selectSpeaker.value;
    const uniqueSpeakers = Array.from(new Set(transcript.map((t) => t.speaker))).sort();

    selectSpeaker.innerHTML = '<option value="">Semua Pembicara</option>';
    uniqueSpeakers.forEach((spk) => {
      const opt = document.createElement('option');
      opt.value = spk;
      opt.textContent = spk;
      if (spk === currentVal) opt.selected = true;
      selectSpeaker.appendChild(opt);
    });
  }

  function renderFeed() {
    const searchTerm = (inputSearch.value || '').toLowerCase().trim();
    const filterSpeaker = selectSpeaker.value;

    const filtered = transcript.filter((t) => {
      const matchSpeaker = filterSpeaker ? t.speaker === filterSpeaker : true;
      const matchSearch = searchTerm
        ? t.speaker.toLowerCase().includes(searchTerm) || t.text.toLowerCase().includes(searchTerm)
        : true;
      return matchSpeaker && matchSearch;
    });

    if (transcript.length === 0) {
      feed.innerHTML = `
        <div class="empty-placeholder">
          <div class="empty-icon">💬</div>
          <h3>Belum Ada Item Caption</h3>
          <p>
            Begitu kotak caption hitam muncul di layar Zoom, setiap item percakapan (beserta foto/inisial seperti <strong>LR</strong>) akan langsung tampil di sini.
          </p>
        </div>
      `;
      return;
    }

    if (filtered.length === 0) {
      feed.innerHTML = `
        <div class="empty-placeholder">
          <div class="empty-icon">🔍</div>
          <h3>Item Tidak Ditemukan</h3>
          <p>Tidak ada item caption yang cocok dengan kata kunci atau filter pembicara.</p>
        </div>
      `;
      return;
    }

    feed.innerHTML = filtered
      .map((t, idx) => {
        const color = getSpeakerColor(t.speaker, t.avatarBg);
        const avatarHtml = t.avatarUrl
          ? `<div class="turn-avatar"><img src="${escapeHtml(t.avatarUrl)}" alt="${escapeHtml(t.speaker)}" /></div>`
          : `<div class="turn-avatar" style="background: ${color};">${escapeHtml(t.initials || getInitials(t.speaker))}</div>`;

        return `
        <div class="turn-item">
          ${avatarHtml}
          <div class="turn-content">
            <div class="turn-meta">
              <div class="turn-meta-left">
                <span class="item-index">#${idx + 1}</span>
                <span class="speaker-tag" data-speaker="${escapeHtml(t.speaker)}" title="Klik untuk ubah nama pembicara" style="background-color: ${color}24; color: #e2e8f0; border: 1px solid ${color}66;">
                  ${escapeHtml(t.speaker)} ✎
                </span>
              </div>
              <div class="turn-meta-right">
                <span class="timestamp">${t.timestamp}</span>
                <button class="btn-copy-item" data-text="${escapeHtml(t.speaker + ': ' + t.text)}" title="Salin item ini">📋</button>
              </div>
            </div>
            <div class="turn-text">${escapeHtml(t.text)}</div>
          </div>
        </div>
      `;
      })
      .join('');

    // Klik nama pembicara untuk rename massal
    feed.querySelectorAll('.speaker-tag').forEach((tag) => {
      tag.addEventListener('click', () => {
        const oldName = tag.getAttribute('data-speaker');
        const newName = prompt(`Ubah nama pembicara "${oldName}" menjadi:`, oldName);
        if (newName && newName.trim()) {
          const trimmed = newName.trim();
          transcript.forEach((item) => {
            if (item.speaker === oldName) {
              item.speaker = trimmed;
              item.initials = getInitials(trimmed);
            }
          });
          chrome.storage.local.set({ zoomTranscript: transcript });
          updateUI();
        }
      });
    });

    // Klik salin per item caption
    feed.querySelectorAll('.btn-copy-item').forEach((btn) => {
      btn.addEventListener('click', () => {
        const txt = btn.getAttribute('data-text') || '';
        navigator.clipboard.writeText(txt).then(() => {
          btn.textContent = '✅';
          setTimeout(() => (btn.textContent = '📋'), 1200);
        });
      });
    });

    feed.scrollTop = feed.scrollHeight;
  }

  function formatText() {
    let out = `TRANSKRIP ITEM CAPTION ZOOM MEETING\n`;
    out += `Waktu: ${new Date().toLocaleString('id-ID')}\n`;
    out += `Total Item Caption: ${transcript.length}\n`;
    out += `==================================================\n\n`;
    transcript.forEach((t, idx) => {
      out += `[#${idx + 1} | ${t.timestamp}] ${t.speaker}:\n${t.text}\n\n`;
    });
    return out;
  }

  function formatMarkdown() {
    let out = `# 📝 Transkrip Item Caption Zoom Meeting\n\n`;
    out += `> **Tanggal:** ${new Date().toLocaleDateString('id-ID')} | **Waktu:** ${new Date().toLocaleTimeString('id-ID')}  \n`;
    out += `> **Total Item Caption:** ${transcript.length} item  \n\n`;

    const stats = {};
    transcript.forEach((t) => {
      if (!stats[t.speaker]) stats[t.speaker] = { count: 0, words: 0 };
      stats[t.speaker].count += 1;
      stats[t.speaker].words += t.text.trim().split(/\s+/).filter(Boolean).length;
    });

    out += `## 👥 Partisipan & Kontribusi\n\n`;
    out += `| Pembicara | Jumlah Item Caption | Total Kata |\n`;
    out += `| :--- | :--- | :--- |\n`;
    Object.entries(stats).forEach(([spk, data]) => {
      out += `| **${spk}** | ${data.count} item | ${data.words} kata |\n`;
    });
    out += `\n---\n\n`;

    out += `## 💬 Daftar Item Caption\n\n`;
    transcript.forEach((t, idx) => {
      out += `### #${idx + 1} · \`${t.timestamp}\` — **${t.speaker}**\n`;
      out += `${t.text}\n\n`;
    });
    return out;
  }

  function downloadFile(content, filename, mimeType) {
    const blob = new Blob([content], { type: mimeType });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }

  // Event Listeners
  inputSearch.addEventListener('input', renderFeed);
  selectSpeaker.addEventListener('change', renderFeed);

  btnPickTarget.addEventListener('click', async () => {
    await ensureInjectedAndScan();
    chrome.storage.local.set({ pickerModeTrigger: Date.now() });
    chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
      if (tabs[0]?.id) {
        chrome.tabs.sendMessage(tabs[0].id, { action: 'startPicker' }, () => {
          window.close();
        });
      } else {
        window.close();
      }
    });
  });

  btnRescan.addEventListener('click', async () => {
    btnRescan.textContent = '⏳ Memindai...';
    await ensureInjectedAndScan();
    setTimeout(() => {
      loadData();
      btnRescan.textContent = '🔄 Pindai Otomatis';
    }, 450);
  });

  btnToggleRecord.addEventListener('click', () => {
    isRecording = !isRecording;
    chrome.storage.local.set({ isRecording });
    updateStatusBadge();
    chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
      if (tabs[0]?.id) {
        chrome.tabs.sendMessage(tabs[0].id, { action: 'toggleRecording', isRecording });
      }
    });
  });

  btnClear.addEventListener('click', () => {
    if (confirm('Yakin ingin menghapus seluruh daftar item caption saat ini?')) {
      transcript = [];
      liveMonitor = null;
      chrome.storage.local.set({ zoomTranscript: [], liveMonitorState: null });
      chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
        if (tabs[0]?.id) {
          chrome.tabs.sendMessage(tabs[0].id, { action: 'clearTranscript' });
        }
      });
      updateUI();
    }
  });

  btnCopy.addEventListener('click', () => {
    if (transcript.length === 0) return alert('Belum ada item caption untuk disalin.');
    navigator.clipboard.writeText(formatText()).then(() => {
      btnCopy.textContent = '✅ Tersalin!';
      setTimeout(() => (btnCopy.textContent = '📋 Salin Semua'), 1800);
    });
  });

  btnExportMd.addEventListener('click', () => {
    if (transcript.length === 0) return alert('Belum ada item caption untuk diunduh.');
    const dateStr = new Date().toISOString().slice(0, 10);
    downloadFile(formatMarkdown(), `transkrip_zoom_${dateStr}.md`, 'text/markdown');
  });

  btnExportTxt.addEventListener('click', () => {
    if (transcript.length === 0) return alert('Belum ada item caption untuk diunduh.');
    const dateStr = new Date().toISOString().slice(0, 10);
    downloadFile(formatText(), `transkrip_zoom_${dateStr}.txt`, 'text/plain');
  });

  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === 'local') {
      if (changes.zoomTranscript) {
        transcript = changes.zoomTranscript.newValue || [];
      }
      if (changes.isRecording !== undefined) {
        isRecording = changes.isRecording.newValue;
      }
      if (changes.liveMonitorState) {
        liveMonitor = changes.liveMonitorState.newValue;
      }
      updateUI();
    }
  });

  ensureInjectedAndScan();
  loadData();
});
