package org.yamcs.sle;

import java.util.List;

/**
 * 
 * Handles a list of SLE endpoints which are tried in a round robin fashion
 */
public class EndpointHandler {
    final List<Endpoint> endpoints;
    int index = -1;
    final long reconnectionIntervalMillis;
    final long roundRobinIntervalMillis;

    public EndpointHandler(SleConfig sconf) {
        this.endpoints = sconf.endpoints;
        this.reconnectionIntervalMillis = sconf.reconnectionIntervalSec * 1000;
        this.roundRobinIntervalMillis = sconf.roundRobinIntervalMillis;

    }

    /**
     * 
     * @return the next point in the list to connect to
     */
    public Endpoint next() {
        if (index < 0 || index >= endpoints.size()) {
            index = 0;
        }

        return endpoints.get(index++);
    }

    /**
     * This is called after a SLE connection failed - returns the amount of time to wait before attempting to connect to
     * the next endpoint
     * <p>
     * If all the endpoints have been exhausted, return {@link #reconnectionIntervalMillis}, otherwise return
     * {@link #roundRobinIntervalMillis}
     * 
     * @return
     */
    public long nextConnectionAttemptMillis() {
        if (index >= endpoints.size()) {
            return reconnectionIntervalMillis;
        } else {
            return roundRobinIntervalMillis;
        }
    }

    /**
     * sets the index to -1 such that he next endpoint returned is the first one in the list
     */
    public void reset() {
        this.index = -1;
    }

    public static class Endpoint {

        private String host;
        private int port;

        public Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

    }

}
