package app.tendereval.backend.exports;

import java.util.List;

/** A table of text cells: the common shape of every export before it becomes a file. */
public record Sheet(String name, List<String> header, List<List<String>> rows) {}
