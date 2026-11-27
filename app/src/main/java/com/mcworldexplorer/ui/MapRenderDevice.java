package com.mcworldexplorer.ui;

import java.util.List;

/** JavaFX 21 exposes the active Prism adapter only through toolkit internals. */
public record MapRenderDevice(String description, String adapterId) {
    public static MapRenderDevice read(String stageTitle) {
        String backend = "JavaFX";
        try {
            Class<?> pipeline = Class.forName("com.sun.prism.GraphicsPipeline");
            Object active = pipeline.getMethod("getPipeline").invoke(null);
            if (active == null) return new MapRenderDevice("JavaFX 尚未初始化", "");
            Class<?> type = active.getClass();
            if (type.getSimpleName().equals("SWPipeline")) return new MapRenderDevice("Prism 软件渲染 · CPU", "");
            backend = type.getSimpleName();
            if (!backend.equals("D3DPipeline")) return new MapRenderDevice(backend, "");
            backend = "Prism Direct3D";
            Class<?> windowType = Class.forName("com.sun.glass.ui.Window");
            for (Object window : (List<?>) windowType.getMethod("getWindows").invoke(null)) {
                if (!stageTitle.equals(windowType.getMethod("getTitle").invoke(window))) continue;
                Object screen = windowType.getMethod("getScreen").invoke(window);
                int adapter = (int) screen.getClass().getMethod("getAdapterOrdinal").invoke(screen);
                if (adapter < 0) break;
                Class<?> info = Class.forName("com.sun.prism.d3d.D3DDriverInformation");
                var constructor = info.getDeclaredConstructor();
                constructor.setAccessible(true);
                var read = type.getDeclaredMethod("nGetDriverInformation", int.class, info);
                read.setAccessible(true);
                Object details = read.invoke(null, adapter, constructor.newInstance());
                if (details == null) break;
                var description = info.getField("deviceDescription");
                description.setAccessible(true);
                return new MapRenderDevice(backend + " · " + description.get(details), adapterId(details));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            org.slf4j.LoggerFactory.getLogger(MapRenderDevice.class).debug("JavaFX adapter query failed at " + backend, unavailable);
        }
        return new MapRenderDevice(backend + " · 无法读取当前设备", "");
    }

    private static String adapterId(Object details) {
        try {
            return "%X&%X&%X".formatted(deviceField(details, "vendorID"), deviceField(details, "deviceID"),
                    deviceField(details, "subSysId"));
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return "";
        }
    }

    private static int deviceField(Object details, String name) throws ReflectiveOperationException {
        var field = details.getClass().getField(name);
        field.setAccessible(true);
        return field.getInt(details);
    }
}
