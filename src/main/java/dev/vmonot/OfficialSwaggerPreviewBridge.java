package dev.vmonot;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.progress.PerformInBackgroundOption;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public final class OfficialSwaggerPreviewBridge {
    private static final String SWAGGER_PLUGIN_ID = "com.intellij.swagger";
    private static final String SW_PREVIEW_BROWSER_CLASS = "com.intellij.swagger.core.ui.browser.SwPreviewCefBrowser";
    private static final String SW_PREVIEW_PROVIDER_STRATEGY_CLASS =
            "com.intellij.swagger.core.ui.browser.strategy.SwPreviewProviderStrategy";
    private static final String REDOC_PROVIDER_STRATEGY_CLASS =
            "com.intellij.swagger.core.ui.browser.strategy.RedocProviderStrategy";
    private static final String SWAGGER_UI_PROVIDER_STRATEGY_CLASS =
            "com.intellij.swagger.core.ui.browser.strategy.SwaggerUiProviderStrategy";

    private OfficialSwaggerPreviewBridge() {
    }

    // Keep Swagger access reflective so Scalar can run when the optional Swagger plugin is absent.
    public static boolean isAvailable() {
        ClassLoader pluginClassLoader = currentPluginClassLoader();
        if (pluginClassLoader == null) {
            return false;
        }

        return loadClass(SW_PREVIEW_BROWSER_CLASS, pluginClassLoader) != null
                && loadClass(SW_PREVIEW_PROVIDER_STRATEGY_CLASS, pluginClassLoader) != null;
    }

    @Nullable
    public static ClassLoader currentPluginClassLoader() {
        PluginId pluginId = PluginId.getId(SWAGGER_PLUGIN_ID);
        if (!PluginManagerCore.isLoaded(pluginId)) {
            return null;
        }

        IdeaPluginDescriptor plugin = PluginManagerCore.getPlugin(pluginId);
        return plugin == null ? null : plugin.getPluginClassLoader();
    }

    public static FileEditor createRedoc(
            @NotNull VirtualFile file,
            @NotNull TextEditor textEditor,
            @NotNull Project project
    ) throws ReflectiveOperationException {
        return createPreview(file, textEditor, project, REDOC_PROVIDER_STRATEGY_CLASS);
    }

    public static FileEditor createSwaggerUi(
            @NotNull VirtualFile file,
            @NotNull TextEditor textEditor,
            @NotNull Project project
    ) throws ReflectiveOperationException {
        return createPreview(file, textEditor, project, SWAGGER_UI_PROVIDER_STRATEGY_CLASS);
    }

    public static boolean reload(@NotNull FileEditor preview, @NotNull VirtualFile file)
            throws ReflectiveOperationException {
        ClassLoader pluginClassLoader = requirePluginClassLoader();
        Class<?> browserClass = requireClass(SW_PREVIEW_BROWSER_CLASS, pluginClassLoader);
        if (!browserClass.isInstance(preview)) {
            return false;
        }

        Method loadHtmlInBackground = browserClass.getMethod(
                "loadHtmlInBackground",
                VirtualFile.class,
                PerformInBackgroundOption.class,
                Function0.class
        );
        loadHtmlInBackground.invoke(
                preview,
                file,
                PerformInBackgroundOption.ALWAYS_BACKGROUND,
                (Function0<Unit>) () -> Unit.INSTANCE
        );

        return true;
    }

    private static FileEditor createPreview(
            VirtualFile file,
            TextEditor textEditor,
            Project project,
            String strategyClassName
    ) throws ReflectiveOperationException {
        ClassLoader pluginClassLoader = requirePluginClassLoader();
        Class<?> browserClass = requireClass(SW_PREVIEW_BROWSER_CLASS, pluginClassLoader);
        Class<?> strategyInterface = requireClass(SW_PREVIEW_PROVIDER_STRATEGY_CLASS, pluginClassLoader);
        Object strategy = strategyInstance(strategyClassName, pluginClassLoader);
        Constructor<?> constructor = browserClass.getConstructor(
                VirtualFile.class,
                TextEditor.class,
                Project.class,
                strategyInterface
        );

        return (FileEditor) constructor.newInstance(file, textEditor, project, strategy);
    }

    private static Object strategyInstance(String strategyClassName, ClassLoader pluginClassLoader)
            throws ReflectiveOperationException {
        return requireClass(strategyClassName, pluginClassLoader).getField("INSTANCE").get(null);
    }

    private static ClassLoader requirePluginClassLoader() throws ClassNotFoundException {
        ClassLoader pluginClassLoader = currentPluginClassLoader();
        if (pluginClassLoader == null) {
            throw new ClassNotFoundException(SWAGGER_PLUGIN_ID);
        }

        return pluginClassLoader;
    }

    private static Class<?> requireClass(String className, ClassLoader pluginClassLoader) throws ClassNotFoundException {
        Class<?> value = loadClass(className, pluginClassLoader);
        if (value == null) {
            throw new ClassNotFoundException(className);
        }

        return value;
    }

    @Nullable
    private static Class<?> loadClass(String className, ClassLoader pluginClassLoader) {
        try {
            return Class.forName(className, true, pluginClassLoader);
        } catch (ClassNotFoundException | LinkageError ignored) {
            return null;
        }
    }
}
