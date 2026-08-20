package dev.isylxnt.duskcontracts.application;

public interface ResultCallback<T> {
    void success(T value);
    void failure(Throwable error, String correlationId);
}
