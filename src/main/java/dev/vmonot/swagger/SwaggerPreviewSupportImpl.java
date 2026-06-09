package dev.vmonot.swagger;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.swagger.core.ui.SwPreviewType;
import com.intellij.swagger.core.ui.browser.SwPreviewCefBrowser;
import com.intellij.swagger.core.ui.browser.strategy.RedocProviderStrategy;
import com.intellij.swagger.core.ui.browser.strategy.SwPreviewProviderStrategy;
import com.intellij.swagger.core.ui.browser.strategy.SwaggerUiProviderStrategy;
import dev.vmonot.SwaggerPreviewSupport;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// Written in Java: the Swagger preview classes are public in bytecode but Kotlin-internal, so a
// Kotlin caller cannot reference them. Registered only via the optional com.intellij.swagger config
// file, so this class loads exclusively when the Swagger plugin is present.
public final class SwaggerPreviewSupportImpl implements SwaggerPreviewSupport {
    @Override
    public @NotNull FileEditor createRedoc(
            @NotNull VirtualFile file,
            @NotNull TextEditor textEditor,
            @NotNull Project project
    ) {
        return new SwPreviewCefBrowser(file, textEditor, project, RedocProviderStrategy.INSTANCE);
    }

    @Override
    public @NotNull FileEditor createSwaggerUi(
            @NotNull VirtualFile file,
            @NotNull TextEditor textEditor,
            @NotNull Project project
    ) {
        return new SwPreviewCefBrowser(file, textEditor, project, SwaggerUiProviderStrategy.INSTANCE);
    }

    @Override
    public boolean reload(@NotNull FileEditor preview, @NotNull VirtualFile file) {
        if (!(preview instanceof SwPreviewCefBrowser browser)) {
            return false;
        }

        // Re-applying the current strategy disposes it, re-installs it, and reloads the browser's
        // file in the background — an in-place refresh that avoids the deprecated loadHtmlInBackground
        // overload (and its deprecated PerformInBackgroundOption parameter).
        SwPreviewProviderStrategy strategy = strategyFor(browser.getSelectedPreviewType());
        if (strategy == null) {
            return false;
        }

        browser.applyStrategy(strategy);
        return true;
    }

    private static @Nullable SwPreviewProviderStrategy strategyFor(@NotNull SwPreviewType previewType) {
        return switch (previewType) {
            case REDOC -> RedocProviderStrategy.INSTANCE;
            case SWAGGER_UI -> SwaggerUiProviderStrategy.INSTANCE;
            default -> null;
        };
    }
}
