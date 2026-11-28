package com.chua.deeplearning.support.agentscope;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.ProjectAwareOverlay;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;

import java.nio.file.Path;

/**
 * 在 SDK 的工作区/项目双层装配中统一使用带真实路径校验的文件系统。
 * shell 继续委托 SDK，由调用方既有开关控制，不构成主机沙箱。
 *
 * @author CH
 * @since 4.0.0.51
 */
final class ContainedLocalFilesystemSpec extends LocalFilesystemSpec {

    private boolean shared;

    @Override
    public LocalFilesystemSpec sharedLocalWorkspace(boolean value) {
        this.shared = value;
        return super.sharedLocalWorkspace(value);
    }

    @Override
    public AbstractFilesystem toFilesystem(Path workspace, NamespaceFactory namespaceFactory) {
        OverlayFilesystem original = (OverlayFilesystem) super.toFilesystem(workspace, namespaceFactory);
        NamespaceFactory namespace = shared ? null : namespaceFactory;
        LocalFilesystem source = (LocalFilesystem) original.upper();
        var upper = new ShellFilesystem(source, namespace, (AbstractSandboxFilesystem) original.upper());
        var lower = new ContainedLocalFilesystem((LocalFilesystem) original.lower(), null);
        if (isProjectWritable()) {
            Path project = getProject() != null ? getProject() : Path.of(System.getProperty("user.dir"));
            var projectSource = new LocalFilesystem(project, getMode(), source.getPathPolicy(), 10, namespace);
            return new ProjectAwareOverlay(upper, lower, new ContainedLocalFilesystem(projectSource, namespace),
                    workspace, namespace);
        }
        return OverlayFilesystem.of(upper, lower);
    }

    /** 文件操作使用父类，shell 参数与执行仍由 SDK 原对象持有。 */
    private static final class ShellFilesystem extends ContainedLocalFilesystem implements AbstractSandboxFilesystem {
        private final AbstractSandboxFilesystem shell;

        private ShellFilesystem(LocalFilesystem source, NamespaceFactory namespace, AbstractSandboxFilesystem shell) {
            super(source, namespace);
            this.shell = shell;
        }

        @Override
        public String id() {
            return shell.id();
        }

        @Override
        public ExecuteResponse execute(RuntimeContext context, String command, Integer timeoutSeconds) {
            return shell.execute(context, command, timeoutSeconds);
        }
    }
}
