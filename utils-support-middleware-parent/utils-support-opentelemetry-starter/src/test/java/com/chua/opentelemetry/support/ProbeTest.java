import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.runtime.protocol.Span;
import com.chua.runtime.protocol.Trace;
import com.chua.opentelemetry.support.client.OpenTelemetryClient;
import org.junit.jupiter.api.Test;

import java.util.List;

class ProbeTest {
    @Test
    void engineSharing() {
        Engine e1 = Engine.create("opentelemetry");
        System.out.println("[probe] FIRST create: closed=" + e1.isClosed() + " class=" + e1.getClass().getName());
        Engine e2 = Engine.create("opentelemetry");
        System.out.println("[probe] SECOND create: same=" + (e1 == e2) + " closed=" + e2.isClosed());
        e1.close();
        System.out.println("[probe] closed; same=" + (e1 == e2) + " closedAgain=" + e2.isClosed());
        Engine e3 = Engine.create("opentelemetry");
        System.out.println("[probe] re-create after close: same=" + (e1 == e3) + " closed=" + e3.isClosed());
        try {
            OpenTelemetryClient c = OpenTelemetryClient.builder().serviceName("test").build();
            io.opentelemetry.api.trace.Span root = c.startSpan("root");
            root.end();
            List<Span> list = c.spans();
            System.out.println("[probe] spans=" + list.size()
                + " parentSpanId='" + list.get(0).getParentSpanId()
                + "' spanId='" + list.get(0).getSpanId()
                + "' emptyEmpty=" + (list.get(0).getParentSpanId() == null || list.get(0).getParentSpanId().isEmpty()));
            List<Trace> traces = c.traces();
            System.out.println("[probe] client traces=" + traces.size() + " root=" + (traces.isEmpty() ? null : traces.get(0).getRoot()));
            c.close();
        } catch (Exception e) {
            e.printStackTrace(System.out);
        }
    }
}
