/**
 * Zoom Caption Transcriber v4.0 — Smart Auto Item Caption Detector
 * Fitur Utama:
 * 1. Deteksi Otomatis Multi-Layer (Selector + Heuristik Visual/Struktural):
 *    Mendeteksi kotak caption Zoom dan langsung mengekstrak per ITEM CAPTION
 *    (Foto Avatar / Badge Inisial seperti "LR" + Teks Ucapan per baris item).
 * 2. Pelacakan Item Real-Time (WeakMap + Tail Sync):
 *    Setiap item caption yang sedang berjalan diperbarui langsung di tempatnya,
 *    dan otomatis membuat item baru saat baris item baru muncul di kotak caption Zoom.
 * 3. Dukungan Multi-Frame (Top Document & #webclient Iframe) via chrome.storage.local.
 */

(function () {
  'use strict';

  if (window.__zoomTranscriberV4Active) return;
  window.__zoomTranscriberV4Active = true;

  let isRecording = true;
  let transcript = [];
  let lockedContainerEl = null;
  let isPickingMode = false;
  let hoveredPickerEl = null;

  // Peta referensi elemen DOM item caption -> ID entri transkrip
  const domItemMap = new WeakMap();
  // Cache resolusi URL avatar -> nama partisipan
  const avatarNameCache = new Map();

  const PALETTE = ['#6366f1', '#ec4899', '#10b981', '#f59e0b', '#06b6d4', '#8b5cf6', '#ef4444'];
  let colorCounter = 0;
  const speakerColors = {};

  function getSpeakerColor(speaker, fallbackBg) {
    if (fallbackBg && fallbackBg !== 'rgba(0, 0, 0, 0)' && fallbackBg !== 'transparent') {
      return fallbackBg;
    }
    if (!speakerColors[speaker]) {
      speakerColors[speaker] = PALETTE[colorCounter++ % PALETTE.length];
    }
    return speakerColors[speaker];
  }

  function getFormattedTimestamp() {
    return new Date().toTimeString().slice(0, 8);
  }

  function cleanText(str) {
    if (!str) return '';
    return str.replace(/\s+/g, ' ').trim();
  }

  function escapeHtml(str) {
    if (!str) return '';
    return str
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function getInitials(name) {
    const clean = cleanText(name);
    if (!clean) return 'P';
    if (clean.length <= 3 && /^[A-Z0-9]+$/i.test(clean)) return clean.toUpperCase();
    const parts = clean.split(' ').filter(Boolean);
    if (parts.length >= 2) {
      return (parts[0][0] + parts[1][0]).toUpperCase();
    }
    return clean.slice(0, 2).toUpperCase();
  }

  // Muat state awal dari storage
  chrome.storage.local.get(['zoomTranscript', 'isRecording', 'liveMonitorState'], (res) => {
    if (Array.isArray(res.zoomTranscript)) {
      transcript = res.zoomTranscript;
    }
    if (typeof res.isRecording === 'boolean') {
      isRecording = res.isRecording;
    }
    updateHudUI();
    updateHudRecordingState();
    if (res.liveMonitorState) {
      updateHudLiveMonitor(res.liveMonitorState);
    }
  });

  // Sinkronisasi antar-frame & popup melalui chrome.storage.onChanged
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area !== 'local') return;

    if (changes.zoomTranscript) {
      transcript = changes.zoomTranscript.newValue || [];
      updateHudUI();
    }
    if (changes.isRecording !== undefined) {
      isRecording = Boolean(changes.isRecording.newValue);
      updateHudRecordingState();
    }
    if (changes.liveMonitorState && changes.liveMonitorState.newValue) {
      updateHudLiveMonitor(changes.liveMonitorState.newValue);
    }
    if (changes.pickerModeTrigger && changes.pickerModeTrigger.newValue) {
      enableTargetPickerLocal();
    }
    if (changes.forceScanTrigger && changes.forceScanTrigger.newValue) {
      lockedContainerEl = null;
      scanAndProcessCaptionItems();
    }
  });

  function saveState(liveState) {
    const payload = {
      zoomTranscript: transcript,
      lastUpdated: Date.now()
    };
    if (liveState) {
      payload.liveMonitorState = liveState;
    }
    chrome.storage.local.set(payload);

    try {
      chrome.runtime.sendMessage({ action: 'updateBadge', count: transcript.length });
    } catch (e) {}

    updateHudUI();
    if (liveState) updateHudLiveMonitor(liveState);
  }

  /**
   * Ambil semua dokumen yang dapat diakses (dokumen utama + iframe same-origin seperti #webclient)
   */
  function getAccessibleDocs() {
    const docs = [document];
    const iframes = document.querySelectorAll('iframe');
    for (const frame of iframes) {
      try {
        if (frame.contentDocument && frame.contentDocument.body) {
          docs.push(frame.contentDocument);
        }
      } catch (e) {}
    }
    return docs;
  }

  /**
   * Coba cari nama asli pembicara dari daftar partisipan / video tile Zoom berdasarkan URL foto avatar
   */
  function resolveSpeakerNameFromAvatarUrl(imgSrc, doc) {
    if (!imgSrc) return null;
    if (avatarNameCache.has(imgSrc)) return avatarNameCache.get(imgSrc);

    const d = doc || document;
    const allImgs = d.querySelectorAll('img');
    for (const img of allImgs) {
      if (img.src === imgSrc) {
        const alt = cleanText(img.alt || img.title || img.getAttribute('aria-label'));
        if (alt && alt.length > 1 && !/avatar|profile|user|icon/i.test(alt)) {
          avatarNameCache.set(imgSrc, alt);
          return alt;
        }
        // Cek apakah ada elemen nama di dekat foto pada daftar partisipan / video
        const card = img.closest('[class*="participant" i], [class*="video" i], [class*="attendee" i], li');
        if (card) {
          const nameNode = card.querySelector('[class*="name" i], [class*="display" i]');
          if (nameNode) {
            const candidate = cleanText(nameNode.textContent);
            if (candidate && candidate.length > 1 && candidate.length < 40) {
              avatarNameCache.set(imgSrc, candidate);
              return candidate;
            }
          }
        }
      }
    }
    return null;
  }

  /**
   * Analisis satu elemen DOM baris item caption untuk memisahkan:
   * - Avatar Foto (<img>) atau Badge Inisial (misal "LR" merah)
   * - Nama Pembicara
   * - Teks Ucapan Bersih (tanpa tercampur huruf inisial atau tombol)
   */
  function parseCaptionItemElement(itemEl, doc) {
    if (!itemEl || !(itemEl instanceof Element)) return null;
    if (itemEl.closest('#zoom-transcriber-hud') || itemEl.closest('#zoom-transcriber-modal')) return null;

    let speaker = '';
    let avatarUrl = '';
    let initials = '';
    let avatarBg = '';
    let avatarNode = null;
    let speakerNode = null;

    // 1. Cek apakah ada gambar avatar (<img>) di dalam item caption
    const imgEl = itemEl.querySelector('img');
    if (imgEl) {
      avatarNode = imgEl.closest('[class*="avatar" i], [class*="img" i], [class*="icon" i]') || imgEl;
      if (avatarNode === itemEl) avatarNode = imgEl;
      avatarUrl = imgEl.src || '';
      const imgLabel = cleanText(imgEl.alt || imgEl.title || imgEl.getAttribute('aria-label'));
      if (imgLabel && imgLabel.length > 1 && !/avatar|icon|image/i.test(imgLabel)) {
        speaker = imgLabel;
      } else if (avatarUrl) {
        speaker = resolveSpeakerNameFromAvatarUrl(avatarUrl, doc) || '';
      }
    }

    // 2. Jika tidak ada <img>, cek apakah anak pertama adalah badge inisial (seperti kotak merah "LR" di Zoom)
    const childElements = Array.from(itemEl.children).filter(
      (c) => !['BUTTON', 'SVG', 'STYLE', 'SCRIPT'].includes(c.tagName)
    );

    if (!avatarNode && childElements.length >= 2) {
      const firstChild = childElements[0];
      const firstText = cleanText(firstChild.textContent);
      const rect = firstChild.getBoundingClientRect();
      const isSmallBadge =
        (rect.width > 0 && rect.width <= 52 && rect.height <= 52) ||
        /avatar|initial|badge|icon|user/i.test(firstChild.className || '');

      if (firstText && firstText.length >= 1 && firstText.length <= 4 && /^[A-Z0-9]+$/i.test(firstText) && isSmallBadge) {
        avatarNode = firstChild;
        initials = firstText.toUpperCase();
        try {
          const cs = (doc.defaultView || window).getComputedStyle(firstChild);
          if (cs && cs.backgroundColor && cs.backgroundColor !== 'rgba(0, 0, 0, 0)') {
            avatarBg = cs.backgroundColor;
          }
        } catch (e) {}
        if (!speaker) speaker = initials;
      }
    }

    // 3. Cek apakah ada elemen nama pembicara eksplisit
    const explicitSpk = itemEl.querySelector('[class*="speaker" i], [class*="username" i], [class*="author" i], [class*="user-name" i]');
    if (explicitSpk && explicitSpk !== itemEl && !explicitSpk.contains(itemEl)) {
      const spkText = cleanText(explicitSpk.textContent).replace(/:$/, '');
      if (spkText && spkText.length >= 1 && spkText.length < 40) {
        speakerNode = explicitSpk;
        speaker = spkText;
      }
    }

    // 4. Ekstrak teks caption murni (tanpa avatarNode, speakerNode, atau tombol pengaturan/gear)
    let rawCaptionText = '';
    if (childElements.length >= 2 && avatarNode) {
      const textParts = [];
      for (const child of childElements) {
        if (child === avatarNode || child.contains(avatarNode) || avatarNode.contains(child)) continue;
        if (child === speakerNode) continue;
        if (child.tagName === 'BUTTON' || child.querySelector('svg') && cleanText(child.textContent).length === 0) continue;
        if (/setting|gear|control|toolbar|resize/i.test(child.className || '')) continue;
        const t = cleanText(child.textContent);
        if (t) textParts.push(t);
      }
      rawCaptionText = cleanText(textParts.join(' '));
    }

    // Fallback: kloning atau ambil textContent dikurangi prefix inisial/speaker
    if (!rawCaptionText) {
      const clone = itemEl.cloneNode(true);
      clone.querySelectorAll('button, svg, style, script, [class*="setting" i], [class*="avatar" i]').forEach((n) => n.remove());
      rawCaptionText = cleanText(clone.textContent);
      if (initials && rawCaptionText.startsWith(initials)) {
        rawCaptionText = cleanText(rawCaptionText.slice(initials.length));
      }
    }

    // Cek pola "Nama Pembicara: Kalimat..."
    const colonMatch = rawCaptionText.match(/^([^:\n]{2,32}):\s+(.+)$/s);
    if (colonMatch) {
      speaker = cleanText(colonMatch[1]);
      rawCaptionText = cleanText(colonMatch[2]);
    }

    if (!rawCaptionText || rawCaptionText.length < 2) return null;
    if (rawCaptionText.includes('Zoom Transcriber') || rawCaptionText.includes('Pilih Tempat Caption')) return null;

    if (!speaker) {
      speaker = avatarUrl ? 'Pembicara' : (initials || 'Pembicara');
    }
    if (!initials) {
      initials = getInitials(speaker);
    }

    return {
      el: itemEl,
      speaker,
      text: rawCaptionText,
      avatarUrl,
      initials,
      avatarBg: getSpeakerColor(speaker, avatarBg)
    };
  }

  /**
   * Periksa apakah sebuah elemen adalah baris ITEM CAPTION Zoom
   * (Memiliki struktur: [Foto Avatar / Inisial "LR"] + [Teks Caption])
   */
  function isLikelyCaptionItemRow(el) {
    if (!el || !(el instanceof Element)) return false;
    if (['SCRIPT', 'STYLE', 'BUTTON', 'SVG', 'INPUT', 'TEXTAREA', 'A'].includes(el.tagName)) return false;
    if (el.closest('#zoom-transcriber-hud') || el.closest('#zoom-transcriber-modal')) return false;

    const children = Array.from(el.children).filter(
      (c) => !['BUTTON', 'SVG', 'STYLE', 'SCRIPT'].includes(c.tagName)
    );
    if (children.length < 2 || children.length > 5) return false;

    const first = children[0];
    const hasAvatarImg = Boolean(first.querySelector('img') || first.tagName === 'IMG');
    const firstText = cleanText(first.textContent);
    const hasInitialsBadge = firstText.length >= 1 && firstText.length <= 3 && /^[A-Z0-9]+$/i.test(firstText);

    if (!hasAvatarImg && !hasInitialsBadge) return false;

    const restText = children
      .slice(1)
      .map((c) => cleanText(c.textContent))
      .join(' ')
      .trim();

    return restText.length >= 2;
  }

  /**
   * Dari sebuah kontainer caption, ambil semua elemen baris item caption di dalamnya
   */
  function extractCaptionItemsFromContainer(containerEl, doc) {
    if (!containerEl || !containerEl.isConnected) return [];

    // 1. Cari dengan selector item spesifik terlebih dahulu
    const itemSelectors = [
      '[class*="live-transcription-subtitle__item" i]',
      '[class*="subtitle-item" i]',
      '[class*="caption-item" i]',
      '[class*="lt-caption__item" i]',
      '[class*="closed-caption__item" i]',
      '[class*="caption-line" i]'
    ].join(', ');

    let itemElements = Array.from(containerEl.querySelectorAll(itemSelectors));

    // 2. Jika tidak ada class spesifik, cari elemen di dalam kontainer yang strukturnya adalah baris [Avatar/Inisial + Teks]
    if (itemElements.length === 0) {
      const descendants = Array.from(containerEl.querySelectorAll('div, li, p'));
      itemElements = descendants.filter((el) => isLikelyCaptionItemRow(el));
      // Hindari parent dan child masuk bersamaan: ambil elemen terdalam yang cocok sebagai baris item
      itemElements = itemElements.filter(
        (el) => !itemElements.some((other) => other !== el && el.contains(other))
      );
    }

    // 3. Jika kontainer itu sendiri adalah baris item tunggal
    if (itemElements.length === 0 && isLikelyCaptionItemRow(containerEl)) {
      itemElements = [containerEl];
    }

    // 4. Fallback terakhir: jika kontainer hanya berisi teks langsung tanpa struktur avatar
    if (itemElements.length === 0) {
      const parsedSelf = parseCaptionItemElement(containerEl, doc);
      return parsedSelf ? [parsedSelf] : [];
    }

    const parsedItems = [];
    for (const el of itemElements) {
      const parsed = parseCaptionItemElement(el, doc);
      if (parsed) parsedItems.push(parsed);
    }
    return parsedItems;
  }

  /**
   * DETEKSI OTOMATIS MULTI-LAYER:
   * Mencari kontainer tempat item caption Zoom berada tanpa perlu klik manual.
   */
  function autoDetectCaptionContainer() {
    const docs = getAccessibleDocs();

    // LAYER 1: Semua selector potensial kotak caption Zoom (diperiksa seluruhnya dengan querySelectorAll)
    const containerSelectors = [
      '[class*="live-transcription-subtitle" i]',
      '[class*="closed-caption" i]',
      '[class*="caption-window" i]',
      '[class*="caption-container" i]',
      '[class*="lt-caption" i]',
      '[class*="subtitle-wrapper" i]',
      '[class*="subtitle-container" i]',
      '[id*="live-transcription" i]',
      '[id*="closed-caption" i]',
      '[aria-label*="caption" i]',
      '[aria-label*="subtitle" i]',
      '[aria-label*="transcription" i]'
    ].join(', ');

    for (const d of docs) {
      const candidates = d.querySelectorAll(containerSelectors);
      for (const cand of candidates) {
        if (cand.closest('#zoom-transcriber-hud') || cand.closest('#zoom-transcriber-modal')) continue;
        // Tolak jika kandidat adalah tombol toolbar bawah (misal tombol CC)
        if (cand.tagName === 'BUTTON' || cand.closest('footer, [class*="footer" i] button')) continue;

        const items = extractCaptionItemsFromContainer(cand, d);
        if (items.length > 0) {
          return { container: cand, doc: d, items };
        }
      }
    }

    // LAYER 2: Heuristik Visual & Struktural (Mendeteksi kotak hitam transparan Zoom yang berisi baris [Avatar/Inisial + Teks])
    for (const d of docs) {
      const allDivs = d.querySelectorAll('div, li');
      const matchedRows = [];

      for (const el of allDivs) {
        if (!isLikelyCaptionItemRow(el)) continue;
        // Pastikan berada di dalam kotak mengambang (floating caption box), bukan panel chat samping
        if (el.closest('[class*="chat-container" i], [class*="participants-container" i]')) continue;
        const rect = el.getBoundingClientRect();
        if (rect.width < 120 || rect.height < 16 || rect.height > 260) continue;
        matchedRows.push(el);
      }

      const leafRows = matchedRows.filter(
        (el) => !matchedRows.some((other) => other !== el && el.contains(other))
      );

      if (leafRows.length > 0) {
        const firstRow = leafRows[0];
        const parentContainer = firstRow.parentElement || firstRow;
        const items = extractCaptionItemsFromContainer(parentContainer, d);
        if (items.length > 0) {
          return { container: parentContainer, doc: d, items };
        }
      }
    }

    return null;
  }

  /**
   * Sinkronkan daftar item caption yang terlihat di kotak Zoom ke dalam array `transcript`
   */
  function syncCaptionItems(items, sourceLabel) {
    if (!items || items.length === 0) return;

    const now = Date.now();
    const formattedTime = getFormattedTimestamp();
    let hasChanges = false;

    for (const item of items) {
      let targetEntry = null;

      // 1. Cek apakah elemen DOM item ini sudah terdaftar di WeakMap
      if (domItemMap.has(item.el)) {
        const mappedId = domItemMap.get(item.el);
        const existing = transcript.find((t) => t.id === mappedId);
        if (existing) {
          const sameSpeaker = existing.speaker.toLowerCase() === item.speaker.toLowerCase();
          const newLower = item.text.toLowerCase();
          const oldLower = existing.text.toLowerCase();
          const isRelatedText =
            newLower.startsWith(oldLower.slice(0, Math.min(12, oldLower.length))) ||
            oldLower.startsWith(newLower.slice(0, Math.min(12, newLower.length))) ||
            existing.text.length < 16;

          if (sameSpeaker && isRelatedText) {
            targetEntry = existing;
          }
        }
      }

      // 2. Jika belum ada di WeakMap (misal baru dirender ulang oleh React), cek 5 item terakhir di transkrip
      if (!targetEntry) {
        const recentSlice = transcript.slice(-5);
        for (let i = recentSlice.length - 1; i >= 0; i--) {
          const recent = recentSlice[i];
          const sameSpeaker =
            recent.speaker.toLowerCase() === item.speaker.toLowerCase() ||
            (recent.avatarUrl && recent.avatarUrl === item.avatarUrl);

          if (!sameSpeaker) continue;

          const oldLower = recent.text.toLowerCase();
          const newLower = item.text.toLowerCase();

          // Teks persis sama atau merupakan kelanjutan kalimat dari item yang sama
          if (
            oldLower === newLower ||
            newLower.startsWith(oldLower) ||
            oldLower.startsWith(newLower)
          ) {
            targetEntry = recent;
            domItemMap.set(item.el, recent.id);
            break;
          }
        }
      }

      // 3. Jika sedang dijeda (Pause), hanya perbarui monitor tanpa mengubah daftar transkrip
      if (!isRecording) continue;

      if (targetEntry) {
        // Perbarui item caption yang sudah ada jika teksnya bertambah/berubah
        if (targetEntry.text !== item.text || (!targetEntry.avatarUrl && item.avatarUrl)) {
          if (item.text.length >= targetEntry.text.length || !targetEntry.text.toLowerCase().startsWith(item.text.toLowerCase())) {
            targetEntry.text = item.text;
          }
          if (item.avatarUrl) targetEntry.avatarUrl = item.avatarUrl;
          if (item.initials) targetEntry.initials = item.initials;
          if (item.avatarBg) targetEntry.avatarBg = item.avatarBg;
          targetEntry.updatedAt = now;
          hasChanges = true;
        }
      } else {
        // Tambahkan sebagai ITEM CAPTION BARU
        const newEntry = {
          id: 'item-' + now + '-' + Math.random().toString(36).slice(2, 6),
          speaker: item.speaker,
          text: item.text,
          initials: item.initials || getInitials(item.speaker),
          avatarUrl: item.avatarUrl || '',
          avatarBg: item.avatarBg || getSpeakerColor(item.speaker),
          timestamp: formattedTime,
          rawTime: now,
          updatedAt: now
        };
        transcript.push(newEntry);
        domItemMap.set(item.el, newEntry.id);
        hasChanges = true;
      }
    }

    // Item terakhir di kotak Zoom adalah item aktif saat ini
    const latestItem = items[items.length - 1];
    const liveState = {
      detected: true,
      speaker: latestItem.speaker,
      rawText: latestItem.text,
      initials: latestItem.initials || getInitials(latestItem.speaker),
      avatarUrl: latestItem.avatarUrl || '',
      avatarBg: latestItem.avatarBg || getSpeakerColor(latestItem.speaker),
      timestamp: formattedTime,
      activeItemCount: items.length,
      updatedAt: now,
      sourceType: sourceLabel
    };

    if (hasChanges) {
      saveState(liveState);
    } else {
      chrome.storage.local.set({ liveMonitorState: liveState });
      updateHudLiveMonitor(liveState);
    }
  }

  /**
   * Siklus Pemindaian Ringan (Berjalan otomatis setiap 600ms)
   */
  function scanAndProcessCaptionItems() {
    // 1. Jika sudah ada kontainer caption yang terkunci dan masih ada di DOM
    if (lockedContainerEl && lockedContainerEl.isConnected) {
      const items = extractCaptionItemsFromContainer(lockedContainerEl, lockedContainerEl.ownerDocument);
      if (items.length > 0) {
        syncCaptionItems(items, 'Terkunci pada Item Caption');
        return;
      }
    }

    // 2. Deteksi otomatis kontainer & item caption Zoom
    const detected = autoDetectCaptionContainer();
    if (detected && detected.items.length > 0) {
      lockedContainerEl = detected.container;
      syncCaptionItems(detected.items, 'Otomatis Terdeteksi');
    }
  }

  /**
   * Mode Pilih Manual Tempat/Item Caption (dengan highlight hover interaktif)
   */
  function enableTargetPickerLocal() {
    if (isPickingMode) return;
    isPickingMode = true;

    const btnPick = document.getElementById('zth-btn-pick');
    if (btnPick) {
      btnPick.textContent = '👆 Arahkan & Klik pada Item Caption Zoom...';
      btnPick.classList.add('zth-picking');
    }

    const docs = getAccessibleDocs();

    const moveHandler = (e) => {
      if (!isPickingMode) return;
      const target = e.target;
      if (!target || !(target instanceof Element)) return;
      if (target.closest('#zoom-transcriber-hud') || target.closest('#zoom-transcriber-modal')) return;

      if (hoveredPickerEl && hoveredPickerEl !== target) {
        hoveredPickerEl.classList.remove('zth-picker-highlight');
      }
      // Cari elemen item atau kontainer caption terdekat untuk di-highlight
      const candidate =
        target.closest('[class*="subtitle" i], [class*="caption" i], [class*="transcription" i]') ||
        target;
      hoveredPickerEl = candidate;
      hoveredPickerEl.classList.add('zth-picker-highlight');
    };

    const clickHandler = (e) => {
      if (!isPickingMode) return;
      const target = e.target;
      if (!target || !(target instanceof Element)) return;
      if (target.closest('#zoom-transcriber-hud') || target.closest('#zoom-transcriber-modal')) return;

      e.preventDefault();
      e.stopPropagation();

      if (hoveredPickerEl) {
        hoveredPickerEl.classList.remove('zth-picker-highlight');
        hoveredPickerEl = null;
      }

      isPickingMode = false;
      docs.forEach((d) => {
        d.removeEventListener('mousemove', moveHandler, true);
        d.removeEventListener('click', clickHandler, true);
      });

      // Jika user mengklik di dalam satu baris item caption, naik ke kontainer daftar itemnya agar semua item terbaca
      let container = target;
      if (isLikelyCaptionItemRow(target) && target.parentElement) {
        container = target.parentElement;
      } else if (target.parentElement && isLikelyCaptionItemRow(target.parentElement) && target.parentElement.parentElement) {
        container = target.parentElement.parentElement;
      } else {
        const closestCaptionBox = target.closest(
          '[class*="live-transcription-subtitle" i], [class*="closed-caption" i], [class*="caption-window" i], [class*="caption-container" i]'
        );
        if (closestCaptionBox) container = closestCaptionBox;
      }

      lockedContainerEl = container;
      chrome.storage.local.set({ pickerModeTrigger: 0 });

      if (btnPick) {
        btnPick.textContent = '✅ Tempat Caption Terkunci (Klik u/ Ubah)';
        btnPick.classList.remove('zth-picking');
      }

      scanAndProcessCaptionItems();
    };

    docs.forEach((d) => {
      d.addEventListener('mousemove', moveHandler, true);
      d.addEventListener('click', clickHandler, true);
    });
  }

  /**
   * Render Avatar HTML (Foto atau Inisial Berwarna seperti "LR")
   */
  function renderAvatarHtml(item) {
    if (item.avatarUrl) {
      return `<div class="zth-avatar"><img src="${escapeHtml(item.avatarUrl)}" alt="${escapeHtml(item.speaker)}" /></div>`;
    }
    const bg = item.avatarBg || getSpeakerColor(item.speaker || 'P');
    const initials = escapeHtml(item.initials || getInitials(item.speaker || 'P'));
    return `<div class="zth-avatar" style="background: ${bg};">${initials}</div>`;
  }

  /**
   * Injeksi Floating HUD Modern (Hanya di top frame agar tidak duplikat)
   */
  function injectHudUI() {
    if (window !== window.top) return;
    if (document.getElementById('zoom-transcriber-hud')) return;

    const hud = document.createElement('div');
    hud.id = 'zoom-transcriber-hud';
    hud.innerHTML = `
      <div class="zth-header" id="zth-drag-handle">
        <div class="zth-title-wrap">
          <span class="zth-status-ring">
            <span class="zth-pulse-dot zth-active" id="zth-dot"></span>
          </span>
          <span class="zth-title">Zoom Caption</span>
          <span class="zth-ver-pill">v4.0 Auto</span>
        </div>
        <div class="zth-controls">
          <button id="zth-btn-pause" title="Jeda / Lanjutkan" class="zth-icon-btn">⏸</button>
          <button id="zth-btn-minimize" title="Perkecil / Perbesar" class="zth-icon-btn">—</button>
        </div>
      </div>
      <div class="zth-body" id="zth-body">
        <div class="zth-stats-row">
          <div class="zth-metric-group">
            <span class="zth-badge"><strong id="zth-turn-count">0</strong> Item</span>
            <span class="zth-badge"><strong id="zth-speaker-count">0</strong> Pembicara</span>
          </div>
          <span class="zth-detect-pill zth-waiting" id="zth-detect-pill">Memindai Otomatis...</span>
        </div>

        <div class="zth-latest-box" id="zth-latest-box">
          <div class="zth-avatar" id="zth-live-avatar">🎙️</div>
          <div class="zth-latest-content">
            <div class="zth-latest-speaker">
              <span id="zth-live-speaker">Menunggu Item Caption...</span>
              <span class="zth-latest-time" id="zth-live-time">--:--</span>
            </div>
            <div class="zth-latest-text" id="zth-live-text">
              Otomatis mendeteksi saat kotak caption muncul di layar Zoom Anda.
            </div>
          </div>
        </div>

        <button class="zth-btn zth-btn-pick" id="zth-btn-pick">
          🎯 Pilih Tempat Caption Manual (Opsional)
        </button>

        <div class="zth-actions">
          <button class="zth-btn zth-btn-primary" id="zth-btn-open-modal">📋 Lihat Semua Item</button>
          <button class="zth-btn zth-btn-secondary" id="zth-btn-quick-export">💾 Unduh .MD</button>
        </div>
      </div>
    `;

    document.body.appendChild(hud);

    const modal = document.createElement('div');
    modal.id = 'zoom-transcriber-modal';
    modal.className = 'zth-modal-hidden';
    modal.innerHTML = `
      <div class="zth-modal-backdrop" id="zth-modal-backdrop"></div>
      <div class="zth-modal-content">
        <div class="zth-modal-header">
          <div>
            <h3>Daftar Item Caption Zoom</h3>
            <div class="zth-modal-subtitle">Klik nama pembicara untuk mengubah nama secara massal</div>
          </div>
          <button id="zth-modal-close" class="zth-icon-btn">✕</button>
        </div>
        <div class="zth-modal-toolbar">
          <input type="text" id="zth-modal-search" placeholder="Cari kata atau pembicara..." class="zth-search-input" />
          <button id="zth-modal-copy" class="zth-btn zth-btn-secondary" style="flex:0 0 auto;">📋 Salin</button>
          <button id="zth-modal-export-txt" class="zth-btn zth-btn-secondary" style="flex:0 0 auto;">📄 .TXT</button>
          <button id="zth-modal-export-md" class="zth-btn zth-btn-primary" style="flex:0 0 auto;">📝 .MD</button>
        </div>
        <div class="zth-modal-list" id="zth-modal-list"></div>
      </div>
    `;
    document.body.appendChild(modal);

    setupHudEvents();
  }

  function setupHudEvents() {
    const hud = document.getElementById('zoom-transcriber-hud');
    const body = document.getElementById('zth-body');
    const btnPause = document.getElementById('zth-btn-pause');
    const btnMin = document.getElementById('zth-btn-minimize');
    const btnPick = document.getElementById('zth-btn-pick');
    const btnOpenModal = document.getElementById('zth-btn-open-modal');
    const btnQuickExport = document.getElementById('zth-btn-quick-export');
    const modal = document.getElementById('zoom-transcriber-modal');
    const modalClose = document.getElementById('zth-modal-close');
    const modalBackdrop = document.getElementById('zth-modal-backdrop');
    const modalCopy = document.getElementById('zth-modal-copy');
    const modalExportTxt = document.getElementById('zth-modal-export-txt');
    const modalExportMd = document.getElementById('zth-modal-export-md');
    const modalSearch = document.getElementById('zth-modal-search');

    if (btnPick) {
      btnPick.addEventListener('click', () => {
        chrome.storage.local.set({ pickerModeTrigger: Date.now() });
        enableTargetPickerLocal();
      });
    }

    if (btnPause) {
      btnPause.addEventListener('click', () => {
        isRecording = !isRecording;
        chrome.storage.local.set({ isRecording });
        updateHudRecordingState();
      });
    }

    if (btnMin) {
      btnMin.addEventListener('click', () => {
        body.classList.toggle('zth-collapsed');
        hud.classList.toggle('zth-minimized');
        btnMin.textContent = body.classList.contains('zth-collapsed') ? '+' : '—';
      });
    }

    if (btnOpenModal) {
      btnOpenModal.addEventListener('click', () => {
        renderModalTranscript();
        modal.classList.remove('zth-modal-hidden');
      });
    }

    if (modalClose) modalClose.addEventListener('click', () => modal.classList.add('zth-modal-hidden'));
    if (modalBackdrop) modalBackdrop.addEventListener('click', () => modal.classList.add('zth-modal-hidden'));

    if (modalCopy) {
      modalCopy.addEventListener('click', () => {
        navigator.clipboard.writeText(formatTranscriptAsText()).then(() => {
          modalCopy.textContent = '✅ Tersalin';
          setTimeout(() => (modalCopy.textContent = '📋 Salin'), 1800);
        });
      });
    }

    const exportMdHandler = () => {
      const dateStr = new Date().toISOString().slice(0, 10);
      downloadFile(formatTranscriptAsMarkdown(), `transkrip_zoom_${dateStr}.md`, 'text/markdown');
    };
    if (btnQuickExport) btnQuickExport.addEventListener('click', exportMdHandler);
    if (modalExportMd) modalExportMd.addEventListener('click', exportMdHandler);

    if (modalExportTxt) {
      modalExportTxt.addEventListener('click', () => {
        const dateStr = new Date().toISOString().slice(0, 10);
        downloadFile(formatTranscriptAsText(), `transkrip_zoom_${dateStr}.txt`, 'text/plain');
      });
    }

    if (modalSearch) {
      modalSearch.addEventListener('input', (e) => renderModalTranscript(e.target.value.toLowerCase()));
    }

    const dragEl = document.getElementById('zth-drag-handle');
    if (hud && dragEl) makeDraggable(hud, dragEl);
  }

  function updateHudRecordingState() {
    const dot = document.getElementById('zth-dot');
    const btnPause = document.getElementById('zth-btn-pause');
    if (!dot || !btnPause) return;
    if (isRecording) {
      dot.style.backgroundColor = '#10b981';
      dot.style.boxShadow = '0 0 10px #10b981';
      dot.classList.add('zth-active');
      btnPause.textContent = '⏸';
    } else {
      dot.style.backgroundColor = '#94a3b8';
      dot.style.boxShadow = 'none';
      dot.classList.remove('zth-active');
      btnPause.textContent = '▶';
    }
  }

  function updateHudLiveMonitor(liveState) {
    if (!liveState) return;
    const detectPill = document.getElementById('zth-detect-pill');
    const latestBox = document.getElementById('zth-latest-box');
    const avatarWrap = document.getElementById('zth-live-avatar');
    const speakerEl = document.getElementById('zth-live-speaker');
    const timeEl = document.getElementById('zth-live-time');
    const textEl = document.getElementById('zth-live-text');

    if (detectPill) {
      detectPill.textContent = `⚡ ${liveState.sourceType || 'Otomatis Terdeteksi'}`;
      detectPill.classList.remove('zth-waiting');
    }
    if (latestBox) {
      latestBox.classList.add('zth-live-active');
    }
    if (avatarWrap) {
      if (liveState.avatarUrl) {
        avatarWrap.innerHTML = `<img src="${escapeHtml(liveState.avatarUrl)}" alt="${escapeHtml(liveState.speaker)}" />`;
        avatarWrap.style.background = 'transparent';
      } else {
        avatarWrap.textContent = liveState.initials || getInitials(liveState.speaker);
        avatarWrap.style.background = liveState.avatarBg || getSpeakerColor(liveState.speaker);
      }
    }
    if (speakerEl) speakerEl.textContent = liveState.speaker;
    if (timeEl) timeEl.textContent = liveState.timestamp || '';
    if (textEl) textEl.textContent = liveState.rawText;
  }

  function updateHudUI() {
    const speakerCountEl = document.getElementById('zth-speaker-count');
    const turnCountEl = document.getElementById('zth-turn-count');
    if (speakerCountEl && turnCountEl) {
      const uniqueSpeakers = new Set(transcript.map((t) => t.speaker));
      speakerCountEl.textContent = String(uniqueSpeakers.size);
      turnCountEl.textContent = String(transcript.length);
    }
    const modal = document.getElementById('zoom-transcriber-modal');
    if (modal && !modal.classList.contains('zth-modal-hidden')) {
      const searchVal = (document.getElementById('zth-modal-search')?.value || '').toLowerCase();
      renderModalTranscript(searchVal);
    }
  }

  function renderModalTranscript(filter = '') {
    const list = document.getElementById('zth-modal-list');
    if (!list) return;

    if (transcript.length === 0) {
      list.innerHTML = `
        <div class="zth-empty-state">
          Belum ada item caption yang tercatat.<br/>
          Aktifkan <strong>Live Captions (CC)</strong> di Zoom—ekstensi akan otomatis mendeteksi setiap item caption yang muncul.
        </div>
      `;
      return;
    }

    const filtered = filter
      ? transcript.filter(
          (t) =>
            t.speaker.toLowerCase().includes(filter) ||
            t.text.toLowerCase().includes(filter)
        )
      : transcript;

    list.innerHTML = filtered
      .map(
        (t, index) => `
      <div class="zth-turn-card">
        ${renderAvatarHtml(t)}
        <div class="zth-turn-main">
          <div class="zth-turn-header">
            <span class="zth-speaker-pill" data-speaker="${escapeHtml(t.speaker)}" title="Klik untuk ubah nama pembicara">
              #${index + 1} · ${escapeHtml(t.speaker)} ✎
            </span>
            <span class="zth-turn-time">${t.timestamp}</span>
          </div>
          <div class="zth-turn-body">${escapeHtml(t.text)}</div>
        </div>
      </div>
    `
      )
      .join('');

    list.querySelectorAll('.zth-speaker-pill').forEach((pill) => {
      pill.addEventListener('click', () => {
        const oldName = pill.getAttribute('data-speaker');
        const newName = prompt(`Ubah nama pembicara "${oldName}" menjadi:`, oldName);
        if (newName && newName.trim()) {
          const trimmed = newName.trim();
          transcript.forEach((t) => {
            if (t.speaker === oldName) {
              t.speaker = trimmed;
              t.initials = getInitials(trimmed);
            }
          });
          saveState();
          renderModalTranscript(filter);
        }
      });
    });
  }

  function formatTranscriptAsText() {
    let out = `TRANSKRIP ITEM CAPTION ZOOM MEETING\n`;
    out += `Waktu Ekspor: ${new Date().toLocaleString('id-ID')}\n`;
    out += `Total Item Caption: ${transcript.length}\n`;
    out += `==================================================\n\n`;
    transcript.forEach((t, idx) => {
      out += `[#${idx + 1} | ${t.timestamp}] ${t.speaker}:\n${t.text}\n\n`;
    });
    return out;
  }

  function formatTranscriptAsMarkdown() {
    let out = `# 📝 Transkrip Zoom Meeting\n\n`;
    out += `> **Tanggal:** ${new Date().toLocaleDateString('id-ID')} | **Waktu:** ${new Date().toLocaleTimeString('id-ID')}  \n`;
    out += `> **Total Item Caption:** ${transcript.length} item  \n\n`;

    const stats = {};
    transcript.forEach((t) => {
      if (!stats[t.speaker]) stats[t.speaker] = { count: 0, words: 0 };
      stats[t.speaker].count += 1;
      stats[t.speaker].words += t.text.trim().split(/\s+/).length;
    });

    out += `## 👥 Ringkasan Partisipan\n\n`;
    out += `| Pembicara | Jumlah Item Caption | Total Kata |\n`;
    out += `| :--- | :--- | :--- |\n`;
    Object.entries(stats).forEach(([spk, data]) => {
      out += `| **${spk}** | ${data.count} item | ${data.words} kata |\n`;
    });
    out += `\n---\n\n## 💬 Daftar Item Caption\n\n`;

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

  function makeDraggable(el, handle) {
    let pos1 = 0, pos2 = 0, pos3 = 0, pos4 = 0;
    handle.onmousedown = (e) => {
      if (e.target.closest('button')) return;
      e.preventDefault();
      pos3 = e.clientX;
      pos4 = e.clientY;
      document.onmouseup = () => {
        document.onmouseup = null;
        document.onmousemove = null;
      };
      document.onmousemove = (ev) => {
        ev.preventDefault();
        pos1 = pos3 - ev.clientX;
        pos2 = pos4 - ev.clientY;
        pos3 = ev.clientX;
        pos4 = ev.clientY;
        el.style.top = el.offsetTop - pos2 + 'px';
        el.style.left = el.offsetLeft - pos1 + 'px';
        el.style.bottom = 'auto';
        el.style.right = 'auto';
      };
    };
  }

  chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
    if (request.action === 'getTranscript') {
      sendResponse({ transcript, isRecording });
    } else if (request.action === 'clearTranscript') {
      transcript = [];
      chrome.storage.local.remove('liveMonitorState');
      saveState();
      sendResponse({ status: 'cleared' });
    } else if (request.action === 'toggleRecording') {
      isRecording = request.isRecording;
      chrome.storage.local.set({ isRecording });
      updateHudRecordingState();
      sendResponse({ isRecording });
    } else if (request.action === 'startPicker') {
      chrome.storage.local.set({ pickerModeTrigger: Date.now() });
      enableTargetPickerLocal();
      sendResponse({ status: 'picking' });
    } else if (request.action === 'forceScan') {
      lockedContainerEl = null;
      scanAndProcessCaptionItems();
      sendResponse({ count: transcript.length });
    }
    return true;
  });

  injectHudUI();
  // Jalankan pemindaian otomatis tiap 600ms (sangat ringan & responsif untuk item caption)
  setInterval(scanAndProcessCaptionItems, 600);
  // Pindai langsung saat pertama kali dimuat
  setTimeout(scanAndProcessCaptionItems, 200);

  // === HUAWEI TABLET LOCAL BRIDGE SYNC (127.0.0.1:18765/sync) & TOUCH PICKER ===
  function syncToHuaweiNativeApp(itemsList) {
    if (!itemsList || !itemsList.length) return;
    try {
      fetch('http://127.0.0.1:18765/sync', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ items: itemsList.slice(-8) })
      }).catch(function () {});
    } catch (e) {}
  }
  setInterval(function () {
    if (isRecording && transcript.length > 0) {
      syncToHuaweiNativeApp(transcript);
    }
  }, 1000);

})();
