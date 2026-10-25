package io.github.josephkelly.quicklock;

/** A chunk-saved witness detects mismatched world/data backups; global data remains authoritative. */
public interface BlockWitness {
    String quicklock$stamp();
    String quicklock$lock();
    void quicklock$witness(String stamp, String lock);
}
