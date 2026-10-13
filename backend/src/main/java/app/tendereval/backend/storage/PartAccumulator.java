package app.tendereval.backend.storage;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.buffer.DataBuffer;

/**
 * Cuts a stream of buffers into parts of exactly {@code partSize} bytes (the last may be shorter),
 * so an upload holds at most one part in memory and the store receives S3 multipart parts of the
 * size the design names (PI 4.4: 8 MiB).
 */
public final class PartAccumulator {

  private final int partSize;
  private ByteArrayOutputStream current;

  public PartAccumulator(int partSize) {
    if (partSize < 5 * 1024 * 1024) {
      throw new IllegalArgumentException("S3 multipart parts need at least 5 MiB");
    }
    this.partSize = partSize;
    this.current = new ByteArrayOutputStream(Math.min(partSize, 1 << 20));
  }

  /** Adds a buffer's bytes and returns every part that became full. */
  public List<byte[]> add(DataBuffer buffer) {
    byte[] bytes = new byte[buffer.readableByteCount()];
    buffer.read(bytes);
    return add(bytes);
  }

  public List<byte[]> add(byte[] bytes) {
    List<byte[]> full = new ArrayList<>(1);
    int offset = 0;
    while (offset < bytes.length) {
      int room = partSize - current.size();
      int take = Math.min(room, bytes.length - offset);
      current.write(bytes, offset, take);
      offset += take;
      if (current.size() == partSize) {
        full.add(current.toByteArray());
        current = new ByteArrayOutputStream(Math.min(partSize, 1 << 20));
      }
    }
    return full;
  }

  /** What is left after the last buffer; empty when the size was an exact multiple. */
  public byte[] rest() {
    byte[] r = current.toByteArray();
    current = new ByteArrayOutputStream(0);
    return r;
  }
}
