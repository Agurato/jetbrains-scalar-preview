(function () {
  const config = window.__OPENAPI_PREVIEW_CONFIG__ || {};

  function decodeBase64Utf8(value) {
    const binary = atob(value);
    const bytes = new Uint8Array(binary.length);

    for (let index = 0; index < binary.length; index += 1) {
      bytes[index] = binary.charCodeAt(index);
    }

    return new TextDecoder('utf-8').decode(bytes);
  }

  function escapeHtml(value) {
    return String(value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }

  function showPreviewError(error) {
    const app = document.getElementById('app');
    const message = error && error.message ? error.message : String(error);

    if (app) {
      app.innerHTML = '<div class="preview-error"><h1>Preview failed to load</h1><p>' + escapeHtml(message) + '</p></div>';
    }
  }

  window.addEventListener('error', (event) => {
    showPreviewError(event.error || event.message);
  });

  window.addEventListener('unhandledrejection', (event) => {
    showPreviewError(event.reason || 'Unhandled preview error');
  });

  const specContent = decodeBase64Utf8(config.encodedSpecification || '');
  const mimeType = config.mimeType || 'application/yaml';
  const specBlob = new Blob([specContent], { type: mimeType });

  window.OpenApiPreview = Object.freeze({
    specContent,
    mimeType,
    specUrl: URL.createObjectURL(specBlob),
    darkTheme: Boolean(config.darkTheme),
    escapeHtml,
    showPreviewError
  });
})();
