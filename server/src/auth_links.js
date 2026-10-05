/* Handle one-time links without ever sending their token to another origin. */
(() => {
  "use strict";
  const token = new URLSearchParams(location.hash.slice(1)).get("token");
  history.replaceState(null, "", location.pathname);
  const result = document.getElementById("result");
  if (location.pathname === "/verify-email") {
    if (!token) { result.textContent = "Missing verification token."; return; }
    fetch("/v1/auth/email/confirm", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ token }) })
      .then(response => { result.textContent = response.ok ? "Email verified. You can return to the app or sign in." : "Link expired or invalid. Request another link from your account panel."; })
      .catch(() => { result.textContent = "Could not contact the server. Please try again."; });
    return;
  }
  const form = document.getElementById("reset");
  if (!form) return;
  if (!token) { form.hidden = true; result.textContent = "Missing reset token."; return; }
  form.addEventListener("submit", async event => {
    event.preventDefault();
    try {
      const response = await fetch("/v1/auth/password-reset/confirm", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ token, password: document.getElementById("password").value }) });
      result.textContent = response.ok ? "Password changed. Return to the app and sign in." : "Link expired or invalid. Request another reset in the app.";
      if (response.ok) form.hidden = true;
    } catch { result.textContent = "Could not contact the server. Please try again."; }
  });
})();
