package org.yamcs.sle;

import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import org.yamcs.YamcsServer;
import org.yamcs.events.EventProducer;
import org.yamcs.jsle.user.AbstractServiceUserHandler;

public class Utils {
    /**
     * close gracefully the SLE connection
     */
    public static void sleStop(AbstractServiceUserHandler suh, SleConfig sconf, EventProducer eventProducer) {
        if (suh == null) {
            return;
        }

        BiFunction<Void, Throwable, Void> unbindHandler = (v, t) -> {
            if (t != null) {
                eventProducer.sendWarning("Unbind failed: " + t.getMessage() + "; closing the connection");
            } else {
                eventProducer.sendInfo("SLE link unbound, closing the connection");
            }
            suh.shutdown();
            return null;
        };

        BiFunction<Void, Throwable, Void> stopHandler = (v, t) -> {
            if (t != null) {
                eventProducer.sendWarning("Stop failed: " + t.getMessage() + "; closing the connection");
                suh.shutdown();
            } else {
                eventProducer.sendInfo("SLE link stopped, sending unbind(" + sconf.unbindReason + ")");
                suh.unbind(sconf.unbindReason).handle(unbindHandler);
            }
            return null;
        };

        switch (suh.getState()) {
        case UNBINDING:
        case UNBOUND:
        case STOPPING:
            eventProducer.sendInfo("Closing the SLE connection");
            suh.shutdown();
            return;
        case READY:
        case BINDING:
            eventProducer.sendInfo("Sending unbind(" + sconf.unbindReason + ")");
            suh.unbind(sconf.unbindReason).handle(unbindHandler);
            break;
        case ACTIVE:
        case STARTING:
            eventProducer.sendInfo("Stopping the SLE connection");
            suh.stop().handle(stopHandler);
            break;
        default:
            throw new IllegalStateException();
        }

        YamcsServer.getServer().getThreadPoolExecutor().schedule(() -> {
            if (suh.isConnected()) {
                eventProducer.sendWarning("SLE link did not shut down cleanly within timeout, closing the connection");
                suh.shutdown();
            }
        }, sconf.maxShutdownDelayMillis, TimeUnit.MILLISECONDS);

    }

}
