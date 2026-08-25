package io.github.syncnuke.client.internal.protocol.syncplay.service.extractor;

@FunctionalInterface
public interface Extractor<T, R> {
    R extract(T source);
}
