(() => {
  const importForm = document.getElementById("import-form");
  const cancelForm = document.getElementById("cancel-job");
  const panel = document.getElementById("job-status");
  const summary = document.getElementById("job-summary");
  const report = document.getElementById("rejection-report");
  if (!importForm || !cancelForm || !panel || !summary || !report) return;
  const localize = (value) => window.IMUNavLocale?.translate(value) || value;

  let upload = null;
  let sawRunning = false;
  const update = async () => {
    try {
      const response = await fetch("/admin/jobs/status", { cache: "no-store" });
      if (!response.ok) return;
      const job = await response.json();
      panel.hidden = !job.status;
      if (!job.status) return;
      const unit = job.phase === "uploading" ? "bytes" : "rows";
      summary.textContent = localize(`${job.kind}: ${job.status} · ${job.phase} · ${job.processed} ${unit} processed · ${job.rejected} rejected`);
      cancelForm.hidden = job.status !== "running";
      report.hidden = !(job.id && job.rejected && job.status === "complete");
      if (job.id) report.href = `/admin/jobs/${job.id}/rejections.csv`;
      if (job.status === "running") sawRunning = true;
      else if (sawRunning) window.location.reload();
    } catch (_) {
      // Keep the current status visible during transient network failures.
    }
  };

  importForm.addEventListener("submit", (event) => {
    event.preventDefault();
    if (upload) return;
    const request = new XMLHttpRequest();
    upload = request;
    sawRunning = true;
    panel.hidden = false;
    cancelForm.hidden = false;
    summary.textContent = localize("seed import: uploading · 0 bytes sent");
    request.open("POST", importForm.action);
    request.upload.onprogress = (progress) => {
      summary.textContent = localize(`seed import: uploading · ${progress.loaded} bytes sent`);
    };
    request.onload = () => {
      upload = null;
      if (request.status >= 400) {
        summary.textContent = localize(`Import failed (HTTP ${request.status}). Check the server log or retry.`);
        cancelForm.hidden = true;
      }
      update();
    };
    request.onerror = () => {
      upload = null;
      summary.textContent = localize("Upload connection failed. Check the server log or retry.");
      cancelForm.hidden = true;
      update();
    };
    request.onabort = () => { upload = null; update(); };
    request.send(new FormData(importForm));
  });

  cancelForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const response = await fetch(cancelForm.action, { method: "POST", body: new FormData(cancelForm) });
    if (response.ok && upload) upload.abort();
    update();
  });

  update();
  setInterval(update, 2000);
})();
