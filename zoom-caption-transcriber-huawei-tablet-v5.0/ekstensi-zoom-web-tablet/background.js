// Background Service Worker v4.0 - Auto Item Caption Transcriber
chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
  if (request.action === "updateBadge") {
    const count = request.count || 0;
    const text = count > 0 ? (count > 99 ? "99+" : count.toString()) : "";
    const tabId = sender.tab?.id;

    if (tabId) {
      chrome.action.setBadgeText({ text, tabId });
      chrome.action.setBadgeBackgroundColor({ color: "#6366f1", tabId });
    } else {
      chrome.action.setBadgeText({ text });
      chrome.action.setBadgeBackgroundColor({ color: "#6366f1" });
    }
    sendResponse({ status: "ok" });
  }
  return true;
});
