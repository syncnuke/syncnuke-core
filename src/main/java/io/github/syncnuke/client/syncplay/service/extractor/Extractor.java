package io.github.syncnuke.client.syncplay.service.extractor;

@FunctionalInterface
public interface Extractor<T, R> {
    R extract(T source);
}
