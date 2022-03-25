package org.yamcs.sle;

import java.util.concurrent.CompletableFuture;

public interface SleLink {
    /**
     * Retrieve the value for the parameter from the SLE provider
     */
    CompletableFuture<SleParameter> getParameter(ParameterName paraName);
}
