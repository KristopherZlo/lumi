package io.github.luma.ui.toolkit;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Small reflective bridge to LDLib2 so the Fabric build can render with LDLib2
 * when a compatible runtime is present without taking a hard compile/runtime
 * dependency on the currently NeoForge-published artifact.
 */
public final class LdLib2ReflectiveUi {

    private static final String UI_ELEMENT = "com.lowdragmc.lowdraglib2.gui.ui.UIElement";
    private static final String UI = "com.lowdragmc.lowdraglib2.gui.ui.UI";
    private static final String MODULAR_UI = "com.lowdragmc.lowdraglib2.gui.ui.ModularUI";
    private static final String MODULAR_UI_SCREEN = "com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen";
    private static final String LABEL = "com.lowdragmc.lowdraglib2.gui.ui.elements.Label";
    private static final String BUTTON = "com.lowdragmc.lowdraglib2.gui.ui.elements.Button";
    private static final String SCROLLER_VIEW = "com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView";
    private static final String STYLE_MANAGER = "com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager";
    private static final String STYLESHEET = "com.lowdragmc.lowdraglib2.gui.ui.style.Stylesheet";
    private static final String UI_EVENT_LISTENER = "com.lowdragmc.lowdraglib2.gui.ui.event.UIEventListener";

    private final Class<?> uiElementClass;
    private final Class<?> uiClass;
    private final Class<?> modularUiClass;
    private final Class<?> modularUiScreenClass;
    private final Class<?> labelClass;
    private final Class<?> buttonClass;
    private final Class<?> scrollerViewClass;
    private final Class<?> stylesheetManagerClass;
    private final Class<?> stylesheetClass;
    private final Class<?> uiEventListenerClass;

    private LdLib2ReflectiveUi(ClassLoader classLoader) throws ClassNotFoundException {
        this.uiElementClass = Class.forName(UI_ELEMENT, false, classLoader);
        this.uiClass = Class.forName(UI, false, classLoader);
        this.modularUiClass = Class.forName(MODULAR_UI, false, classLoader);
        this.modularUiScreenClass = Class.forName(MODULAR_UI_SCREEN, false, classLoader);
        this.labelClass = Class.forName(LABEL, false, classLoader);
        this.buttonClass = Class.forName(BUTTON, false, classLoader);
        this.scrollerViewClass = Class.forName(SCROLLER_VIEW, false, classLoader);
        this.stylesheetManagerClass = Class.forName(STYLE_MANAGER, false, classLoader);
        this.stylesheetClass = Class.forName(STYLESHEET, false, classLoader);
        this.uiEventListenerClass = Class.forName(UI_EVENT_LISTENER, false, classLoader);
    }

    public static Optional<LdLib2ReflectiveUi> create(ClassLoader classLoader) {
        try {
            return Optional.of(new LdLib2ReflectiveUi(classLoader));
        } catch (ClassNotFoundException exception) {
            return Optional.empty();
        }
    }

    public Object element(String id, String... classes) {
        Object element = this.newInstance(this.uiElementClass);
        this.identify(element, id, classes);
        return element;
    }

    public Object panel(String id) {
        Object element = this.element(id, "panel_bg");
        return element;
    }

    public Object label(String id, Component text, TextTone tone) {
        Object label = this.newInstance(this.labelClass);
        this.identify(label, id);
        this.invoke(label, "setText", new Class<?>[] {Component.class}, text);
        this.layout(label, layout -> layout.widthPercent(100).minHeight(9));
        this.invoke(label, "textStyle", new Class<?>[] {Consumer.class}, (Consumer<Object>) style -> {
            this.invoke(style, "adaptiveHeight", new Class<?>[] {boolean.class}, true);
            this.invoke(style, "textShadow", new Class<?>[] {boolean.class}, tone.shadow);
            this.invoke(style, "textColor", new Class<?>[] {int.class}, tone.color);
            this.invoke(style, "fontSize", new Class<?>[] {float.class}, tone.fontSize);
        });
        return label;
    }

    public Object button(String id, Component text, Runnable action) {
        Object button = this.newInstance(this.buttonClass);
        this.identify(button, id, "lumi-action-button");
        this.invoke(button, "setText", new Class<?>[] {Component.class}, text);
        this.layout(button, layout -> layout.height(18).paddingHorizontal(5).paddingVertical(2));
        this.invoke(button, "setOnClick", new Class<?>[] {this.uiEventListenerClass}, this.listener(action));
        return button;
    }

    public Object scroller(String id) {
        Object scroller = this.newInstance(this.scrollerViewClass);
        this.identify(scroller, id);
        this.layout(scroller, layout -> layout.widthPercent(100).flex(1).minHeight(0));
        this.invoke(scroller, "viewPort", new Class<?>[] {Consumer.class}, (Consumer<Object>) viewPort -> {
            this.layout(viewPort, layout -> layout.paddingAll(3));
        });
        this.invoke(scroller, "viewContainer", new Class<?>[] {Consumer.class}, (Consumer<Object>) viewContainer -> {
            this.layout(viewContainer, layout -> layout.widthPercent(100).gapAll(5));
        });
        return scroller;
    }

    public void addChild(Object parent, Object child) {
        this.invoke(parent, "addChild", new Class<?>[] {this.uiElementClass}, child);
    }

    public void addScrollChild(Object scroller, Object child) {
        this.invoke(scroller, "addScrollViewChild", new Class<?>[] {this.uiElementClass}, child);
    }

    public void layout(Object element, Consumer<LayoutOps> layout) {
        this.invoke(element, "layout", new Class<?>[] {Consumer.class}, (Consumer<Object>) rawLayout -> {
            layout.accept(new LayoutOps(rawLayout));
        });
    }

    public void active(Object element, boolean active) {
        this.invoke(element, "setActive", new Class<?>[] {boolean.class}, active);
    }

    public Screen screen(Object root, Component title) {
        try {
            Object stylesheetManager = this.stylesheetManagerClass.getField("INSTANCE").get(null);
            Object gdp = this.stylesheetManagerClass.getField("GDP").get(null);
            Method stylesheetSafe = this.stylesheetManagerClass.getMethod("getStylesheetSafe", gdp.getClass());
            Object stylesheet = stylesheetSafe.invoke(stylesheetManager, gdp);
            Object stylesheets = Array.newInstance(this.stylesheetClass, 1);
            Array.set(stylesheets, 0, stylesheet);

            Object ui = this.uiClass.getMethod("of", this.uiElementClass, stylesheets.getClass())
                    .invoke(null, root, stylesheets);
            Object modularUi = this.modularUiClass.getMethod("of", this.uiClass).invoke(null, ui);
            Object screen = this.modularUiScreenClass
                    .getConstructor(this.modularUiClass, Component.class)
                    .newInstance(modularUi, title);
            return (Screen) screen;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to create LDLib2 GDP screen.", exception);
        }
    }

    private void identify(Object element, String id, String... classes) {
        if (id != null && !id.isBlank()) {
            this.invoke(element, "setId", new Class<?>[] {String.class}, id);
        }
        for (String cssClass : classes) {
            this.invoke(element, "addClass", new Class<?>[] {String.class}, cssClass);
        }
    }

    private Object listener(Runnable action) {
        return Proxy.newProxyInstance(
                this.uiEventListenerClass.getClassLoader(),
                new Class<?>[] {this.uiEventListenerClass},
                (proxy, method, args) -> {
                    if ("handleEvent".equals(method.getName())) {
                        action.run();
                    }
                    return null;
                }
        );
    }

    private Object newInstance(Class<?> type) {
        try {
            return type.getConstructor().newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to create LDLib2 element " + type.getName(), exception);
        }
    }

    private Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            return target.getClass().getMethod(methodName, parameterTypes).invoke(target, args);
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException("LDLib2 method is unavailable: " + methodName, exception);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("LDLib2 method is inaccessible: " + methodName, exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new IllegalStateException("LDLib2 method failed: " + methodName, cause);
        }
    }

    public final class LayoutOps {

        private final Object layout;

        private LayoutOps(Object layout) {
            this.layout = layout;
        }

        public LayoutOps width(float value) {
            return this.call("width", float.class, value);
        }

        public LayoutOps widthPercent(float value) {
            return this.call("widthPercent", float.class, value);
        }

        public LayoutOps height(float value) {
            return this.call("height", float.class, value);
        }

        public LayoutOps heightPercent(float value) {
            return this.call("heightPercent", float.class, value);
        }

        public LayoutOps minHeight(float value) {
            return this.call("minHeight", float.class, value);
        }

        public LayoutOps flex(float value) {
            return this.call("flex", float.class, value);
        }

        public LayoutOps flexShrink(float value) {
            return this.call("flexShrink", float.class, value);
        }

        public LayoutOps paddingAll(float value) {
            return this.call("paddingAll", float.class, value);
        }

        public LayoutOps paddingHorizontal(float value) {
            return this.call("paddingHorizontal", float.class, value);
        }

        public LayoutOps paddingVertical(float value) {
            return this.call("paddingVertical", float.class, value);
        }

        public LayoutOps gapAll(float value) {
            return this.call("gapAll", float.class, value);
        }

        public LayoutOps row() {
            return this.flexDirection("ROW");
        }

        public LayoutOps column() {
            return this.flexDirection("COLUMN");
        }

        private LayoutOps flexDirection(String constantName) {
            for (Method method : this.layout.getClass().getMethods()) {
                if (!"flexDirection".equals(method.getName()) || method.getParameterCount() != 1) {
                    continue;
                }
                Class<?> parameterType = method.getParameterTypes()[0];
                if (!parameterType.isEnum()) {
                    continue;
                }
                Object constant = Enum.valueOf(parameterType.asSubclass(Enum.class), constantName);
                LdLib2ReflectiveUi.this.invoke(this.layout, method.getName(), new Class<?>[] {parameterType}, constant);
                return this;
            }
            return this;
        }

        private LayoutOps call(String methodName, Class<?> parameterType, Object value) {
            LdLib2ReflectiveUi.this.invoke(this.layout, methodName, new Class<?>[] {parameterType}, value);
            return this;
        }
    }

    public enum TextTone {
        TITLE(0xF4F1EA, 9.0F, false),
        VALUE(0xF4F1EA, 8.0F, true),
        MUTED(0xA9A39A, 8.0F, false),
        ACCENT(0xD9B86C, 8.0F, false),
        DANGER(0xFF8585, 8.0F, false);

        private final int color;
        private final float fontSize;
        private final boolean shadow;

        TextTone(int color, float fontSize, boolean shadow) {
            this.color = color;
            this.fontSize = fontSize;
            this.shadow = shadow;
        }
    }
}
