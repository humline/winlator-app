package com.winlator.contentdialog;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.winlator.R;
import com.winlator.container.Container;
import com.winlator.container.DXWrappers;
import com.winlator.container.GraphicsDrivers;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.GPUHelper;
import com.winlator.core.GeneralComponents;
import com.winlator.core.KeyValueSet;

/**
 * Read-only report of the graphics stack: GPU info, Vulkan capabilities,
 * selected driver packages, wrapper paths and env overrides. Copyable to the
 * clipboard for bug reports and benchmark runs.
 */
public class GraphicsDiagnosticsDialog extends ContentDialog {
    private final String report;

    public GraphicsDiagnosticsDialog(@NonNull Activity activity, Container container) {
        super(activity, R.layout.graphics_diagnostics_dialog);
        setTitle(R.string.graphics_diagnostics);
        setIcon(R.drawable.icon_info);

        String graphicsDriver = container != null ? container.getGraphicsDriver() : GraphicsDrivers.getDefaultDriver(activity);
        String graphicsDriverConfig = container != null ? container.getGraphicsDriverConfig() : "";
        String dxwrapper = container != null ? container.getDXWrapper() : Container.DEFAULT_DXWRAPPER;
        String dxwrapperConfig = container != null ? container.getDXWrapperConfig() : "";

        this.report = buildReport(activity, graphicsDriver, graphicsDriverConfig, dxwrapper, dxwrapperConfig);

        ((TextView)findViewById(R.id.TVGraphicsDiagnostics)).setText(report);
        ((TextView)findViewById(R.id.BTConfirm)).setText(R.string.copy);
        findViewById(R.id.BTCancel).setVisibility(android.view.View.GONE);

        setOnConfirmCallback(() -> {
            ClipboardManager clipboardManager = (ClipboardManager)activity.getSystemService(Activity.CLIPBOARD_SERVICE);
            if (clipboardManager != null) clipboardManager.setPrimaryClip(ClipData.newPlainText("winlator-graphics-diagnostics", report));
        });
    }
    private static String buildReport(Activity activity, String graphicsDriver, String graphicsDriverConfig, String dxwrapper, String dxwrapperConfig) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        String[] identifiers = GraphicsDrivers.parseIdentifiers(graphicsDriver);
        KeyValueSet[] configs = GraphicsDrivers.parseConfigs(graphicsDriver, graphicsDriverConfig);

        StringBuilder builder = new StringBuilder();
        builder.append("GPU\n");
        builder.append("  Renderer: ").append(GPUHelper.glGetRenderer(activity)).append("\n");
        builder.append("  Vendor: ").append(GPUHelper.glGetVendor(activity)).append("\n");
        builder.append("  Version: ").append(GPUHelper.glGetVersion(activity)).append("\n");

        int vkApiVersion = GPUHelper.vkGetApiVersion();
        builder.append("  Vulkan API: ").append(GPUHelper.vkVersionMajor(vkApiVersion))
            .append(".").append(GPUHelper.vkVersionMinor(vkApiVersion))
            .append(".").append(GPUHelper.vkVersionPatch(vkApiVersion)).append("\n");

        StringBuilder extensions = new StringBuilder();
        for (String extension : GPUHelper.vkGetDeviceExtensions()) {
            if (extensions.length() > 0) extensions.append(", ");
            extensions.append(extension);
        }
        builder.append("  Device Extensions: ").append(extensions).append("\n");

        String vulkanVersion = identifiers[0].equals(GraphicsDrivers.TURNIP)
            ? configs[0].get("version", DefaultVersion.TURNIP)
            : DefaultVersion.valueOf(identifiers[0]);

        builder.append("\nDrivers\n");
        builder.append("  Vulkan: ").append(identifiers[0]).append("-").append(vulkanVersion).append("\n");
        builder.append("  OpenGL: ").append(identifiers[1]).append("-").append(DefaultVersion.valueOf(identifiers[1])).append("\n");
        builder.append("  Cache: ").append(preferences.getString("current_graphics_driver", "-")).append("\n");

        KeyValueSet[] dxConfigs = DXWrappers.parseConfigs(dxwrapper, dxwrapperConfig);
        builder.append("\nWrapper Paths\n");
        builder.append("  Turnip: ").append(componentPath(activity, GeneralComponents.Type.TURNIP, vulkanVersion)).append("\n");
        builder.append("  DXVK: ").append(componentPath(activity, GeneralComponents.Type.DXVK, dxConfigs[0].get("version", DefaultVersion.DXVK(identifiers[0])))).append("\n");
        builder.append("  VKD3D: ").append(componentPath(activity, GeneralComponents.Type.VKD3D, dxConfigs[1].get("version", DefaultVersion.VKD3D))).append("\n");
        builder.append("  WineD3D: ").append(componentPath(activity, GeneralComponents.Type.WINED3D, DefaultVersion.WINED3D)).append("\n");

        builder.append("\nConfig\n");
        builder.append("  Vulkan: ").append(configs[0].toString()).append("\n");
        builder.append("  OpenGL: ").append(configs[1].toString()).append("\n");
        builder.append("  DXWrapper: ").append(dxwrapper).append(" ").append(dxConfigs[0].toString()).append("\n");

        builder.append("\nEnv Overrides\n");
        EnvVars envVars = new EnvVars();
        if (identifiers[0].equals(GraphicsDrivers.TURNIP)) {
            TurnipConfigDialog.setEnvVars(activity, configs[0], envVars);
            builder.append("  ").append(envVars.toString()).append("\n");
        }
        else if (identifiers[0].equals(GraphicsDrivers.VORTEK)) {
            builder.append("  vkMaxVersion: ").append(configs[0].get("vkMaxVersion", VortekConfigDialog.DEFAULT_VK_MAX_VERSION)).append("\n");
            String adrenotoolsDriver = configs[0].get("adrenotoolsDriver", "");
            if (!adrenotoolsDriver.isEmpty()) builder.append("  adrenotoolsDriver: ").append(adrenotoolsDriver).append("\n");
        }

        return builder.toString();
    }

    private static String componentPath(Activity activity, GeneralComponents.Type type, String identifier) {
        String path = GeneralComponents.getDefinitivePath(type, activity, identifier);
        return path != null ? path : "-";
    }
}