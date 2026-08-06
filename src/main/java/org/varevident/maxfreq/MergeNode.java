package org.varevident.maxfreq;

/**
 * Priority-queue entry for merging sorted source readers.
 *
 * @author Milad EIDI
 */
record MergeNode(AnnovarSourceReader reader, SourceAggregate aggregate) implements Comparable<MergeNode> {
    @Override
    public int compareTo(MergeNode other) {
        int cmp = aggregate.key().compareTo(other.aggregate.key());
        if (cmp != 0) {
            return cmp;
        }
        return reader.spec().name().compareTo(other.reader.spec().name());
    }
}
