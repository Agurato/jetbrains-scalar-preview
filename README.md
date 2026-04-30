# Scalar OpenAPI Preview

JetBrains IDE plugin that adds a preview editor for OpenAPI specifications, with Scalar as the default renderer.

## What It Does

- Detects `.yaml`, `.yml`, and `.json` files that contain an `openapi` or `swagger` marker.
- Opens those files with a split editor and OpenAPI preview.
- Renders the preview with Scalar without requiring JetBrains' official OpenAPI/Swagger plugin.
- Adds Redoc and Swagger UI renderer options when JetBrains' `com.intellij.swagger` plugin is loaded.
- Keeps Scalar browser assets bundled in this plugin, so Scalar preview does not depend on CDN availability.

## Renderers

| Renderer | Availability | Implementation |
| --- | --- | --- |
| Scalar | Always available | Bundled local `@scalar/api-reference` asset |
| Redoc | Only when `com.intellij.swagger` is loaded | JetBrains official Swagger/OpenAPI preview renderer |
| Swagger UI | Only when `com.intellij.swagger` is loaded | JetBrains official Swagger/OpenAPI preview renderer |

The renderer switch button only appears when more than one renderer is available.

## Compatibility

- Requires an IntelliJ Platform IDE with build `253+`.
- Does not require the YAML plugin.
- Does not require JetBrains' official OpenAPI/Swagger plugin for Scalar preview.
- Requires JCEF, the embedded Chromium browser used by JetBrains IDEs, to display the HTML/JavaScript preview.

In normal modern JetBrains IDE installations, JCEF is included. The requirement mainly matters for unusual, headless, or stripped-down IDE runtimes.

## Offline Behavior

Scalar assets are packaged in this plugin under `src/main/resources/preview/vendor`.

When Redoc or Swagger UI are available, their renderer assets are provided by the official JetBrains Swagger/OpenAPI plugin. The preview UI itself should not need internet access, but OpenAPI documents that reference remote schemas or remote files may still require network access for those references.

## Development

Run the plugin in a sandbox IDE:

```bash
./gradlew runIde
```

Build the plugin distribution:

```bash
./gradlew buildPlugin -x buildSearchableOptions -x prepareJarSearchableOptions -x jarSearchableOptions
```

The built plugin zip is written to:

```text
build/distributions/scalar-openapi-preview-1.0.0-SNAPSHOT.zip
```

Sample OpenAPI files are available in:

```text
src/test/testData/openapi
```

## Notes

`runIde` suppresses the bundled Kubernetes plugin in the sandbox because IU 2025.3.1 can log unrelated Kubernetes remote API errors during development.

Redoc and Swagger UI are optional integrations. The bridge to JetBrains' official Swagger/OpenAPI preview uses reflection and the currently loaded Swagger plugin classloader, so Scalar can keep working when the official plugin is missing, disabled, unloaded, or reloaded.
