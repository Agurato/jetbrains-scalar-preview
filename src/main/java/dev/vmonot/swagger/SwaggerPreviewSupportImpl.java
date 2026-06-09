package dev.vmonot.swagger;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.progress.PerformInBackgroundOption;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.swagger.core.ui.browser.SwPreviewCefBrowser;
import com.intellij.swagger.core.ui.browser.strategy.RedocProviderStrategy;
import com.intellij.swagger.core.ui.browser.strategy.SwaggerUiProviderStrategy;
import dev.vmonot.SwaggerPreviewSupport;
import kotlin.Unit;
import org.jetbrains.annotations.NotNull;

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
    @SuppressWarnings("deprecation") // loadHtmlInBackground still requires the deprecated PerformInBackgroundOption.
    public boolean reload(@NotNull FileEditor preview, @NotNull VirtualFile file) {
        if (!(preview instanceof SwPreviewCefBrowser browser)) {
            return false;
        }

        browser.loadHtmlInBackground(file, PerformInBackgroundOption.ALWAYS_BACKGROUND, () -> Unit.INSTANCE);
        return true;
    }
}
