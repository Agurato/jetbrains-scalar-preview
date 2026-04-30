try {
  Scalar.createApiReference('#app', {
    content: window.OpenApiPreview.specContent,
    darkMode: window.OpenApiPreview.darkTheme,
    forceDarkModeState: window.OpenApiPreview.darkTheme ? 'dark' : 'light',
    documentDownloadType: 'both',
    defaultHttpClient: {
      targetKey: 'shell',
      clientKey: 'curl'
    },
    agent: {
      disabled: true
    }
  });
} catch (error) {
  window.OpenApiPreview.showPreviewError(error);
}
