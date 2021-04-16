package com.launchdarkly.sdk.android;

class ConnectionInformationState implements ConnectionInformation {
    private ConnectionMode connectionMode;
    private LDFailure lastFailure;
    private Long lastSuccessfulConnection;
    private Long lastFailedConnection;

    public ConnectionMode getConnectionMode() {
        return connectionMode;
    }

    void setConnectionMode(ConnectionMode connectionMode) {
        this.connectionMode = connectionMode;
    }

    public LDFailure getLastFailure() {
        return lastFailure;
    }

    void setLastFailure(LDFailure lastFailure) {
        this.lastFailure = lastFailure;
    }

    public Long getLastSuccessfulConnection() {
        return lastSuccessfulConnection;
    }

    void setLastSuccessfulConnection(Long lastSuccessfulConnection) {
        this.lastSuccessfulConnection = lastSuccessfulConnection;
    }

    public Long getLastFailedConnection() {
        return lastFailedConnection;
    }

    void setLastFailedConnection(Long lastFailedConnection) {
        this.lastFailedConnection = lastFailedConnection;
    }
}
