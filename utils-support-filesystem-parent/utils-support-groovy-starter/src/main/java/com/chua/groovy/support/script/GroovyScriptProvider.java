package com.chua.groovy.support.script;

import com.chua.common.support.lang.script.marker.listener.FileScriptListener;
import com.chua.common.support.lang.script.marker.listener.Listener;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.task.script.ScriptProvider;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Spi("groovy")
public class GroovyScriptProvider implements ScriptProvider {

    private final Map<Path, Listener> listenerCache = new ConcurrentHashMap<>();

    @Override
    public String engineName() {
        return "groovy";
    }

    @Override
    public boolean loadScript(Path scriptPath) {
        try {
            Listener listener = new FileScriptListener(scriptPath);
            listenerCache.put(scriptPath, listener);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Object executeScript(Path scriptPath, Object context) {
        Listener listener = listenerCache.computeIfAbsent(scriptPath, FileScriptListener::new);
        String source = listener.getSource();
        if (source == null || source.isEmpty()) {
            throw new IllegalStateException("脚本源码为空: " + scriptPath);
        }

        Binding binding = new Binding();
        if (context != null) {
            binding.setProperty("context", context);
            if (context instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) context).entrySet()) {
                    if (entry.getKey() instanceof String) {
                        binding.setProperty((String) entry.getKey(), entry.getValue());
                    }
                }
            }
        }

        GroovyShell shell = new GroovyShell(binding);
        return shell.evaluate(source);
    }

    @Override
    public void unloadScript(Path scriptPath) {
        listenerCache.remove(scriptPath);
    }

    @Override
    public boolean isLoaded(Path scriptPath) {
        return listenerCache.containsKey(scriptPath);
    }
}
