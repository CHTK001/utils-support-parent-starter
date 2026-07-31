package com.chua.common.support.objects;

import java.util.ArrayList;
import java.util.List;

public class ObjectContextSetting {
    private boolean spiEnabled;
    private boolean annotationScanEnabled;
    private final List<String> scanPackages = new ArrayList<>();

    public static ObjectContextSettingBuilder builder() {
        return new ObjectContextSettingBuilder();
    }

    public boolean isSpiEnabled() { return spiEnabled; }
    public ObjectContextSetting setSpiEnabled(boolean spiEnabled) { this.spiEnabled = spiEnabled; return this; }
    public boolean isAnnotationScanEnabled() { return annotationScanEnabled; }
    public ObjectContextSetting setAnnotationScanEnabled(boolean annotationScanEnabled) { this.annotationScanEnabled = annotationScanEnabled; return this; }
    public List<String> getScanPackages() { return scanPackages; }
    public ObjectContextSetting addScanPackage(String scanPackage) { this.scanPackages.add(scanPackage); return this; }

    public static class ObjectContextSettingBuilder {
        private boolean spiEnabled;
        private boolean annotationScanEnabled;
        private final List<String> scanPackages = new ArrayList<>();

        public ObjectContextSettingBuilder spiEnabled(boolean spiEnabled) { this.spiEnabled = spiEnabled; return this; }
        public ObjectContextSettingBuilder annotationScanEnabled(boolean annotationScanEnabled) { this.annotationScanEnabled = annotationScanEnabled; return this; }
        public ObjectContextSettingBuilder scanPackage(String scanPackage) { this.scanPackages.add(scanPackage); return this; }
        public ObjectContextSetting build() {
            ObjectContextSetting setting = new ObjectContextSetting();
            setting.setSpiEnabled(spiEnabled);
            setting.setAnnotationScanEnabled(annotationScanEnabled);
            setting.getScanPackages().addAll(scanPackages);
            return setting;
        }
    }
}
