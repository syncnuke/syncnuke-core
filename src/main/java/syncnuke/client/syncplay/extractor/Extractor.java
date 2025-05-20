package syncnuke.client.syncplay.extractor;

@FunctionalInterface
public interface Extractor<T, R> {
    R extract(T source);
}
