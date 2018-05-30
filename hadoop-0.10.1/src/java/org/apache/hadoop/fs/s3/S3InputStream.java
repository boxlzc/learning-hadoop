package org.apache.hadoop.fs.s3;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSInputStream;

class S3InputStream extends FSInputStream {

  private int bufferSize;

  private FileSystemStore store;

  private Block[] blocks;

  private boolean closed;

  private long fileLength;

  private long pos = 0;

  private DataInputStream blockStream;

  private long blockEnd = -1;

  public S3InputStream(Configuration conf, FileSystemStore store,
      INode inode) {
    
    this.store = store;
    this.blocks = inode.getBlocks();
    for (Block block : blocks) {
      this.fileLength += block.getLength();
    }
    this.bufferSize = conf.getInt("io.file.buffer.size", 4096);    
  }

  @Override
  public synchronized long getPos() throws IOException {
    return pos;
  }

  @Override
  public synchronized int available() throws IOException {
    return (int) (fileLength - pos);
  }

  @Override
  public synchronized void seek(long targetPos) throws IOException {
    if (targetPos > fileLength) {
      throw new IOException("Cannot seek after EOF");
    }
    pos = targetPos;
    blockEnd = -1;
  }

  @Override
  public synchronized int read() throws IOException {
    if (closed) {
      throw new IOException("Stream closed");
    }
    int result = -1;
    if (pos < fileLength) {
      if (pos > blockEnd) {
        blockSeekTo(pos);
      }
      result = blockStream.read();
      if (result >= 0) {
        pos++;
      }
    }
    return result;
  }

  @Override
  public synchronized int read(byte buf[], int off, int len) throws IOException {
    if (closed) {
      throw new IOException("Stream closed");
    }
    if (pos < fileLength) {
      if (pos > blockEnd) {
        blockSeekTo(pos);
      }
      int realLen = Math.min(len, (int) (blockEnd - pos + 1));
      int result = blockStream.read(buf, off, realLen);
      if (result >= 0) {
        pos += result;
      }
      return result;
    }
    return -1;
  }

  private synchronized void blockSeekTo(long target) throws IOException {
    //
    // Compute desired block
    //
    int targetBlock = -1;
    long targetBlockStart = 0;
    long targetBlockEnd = 0;
    for (int i = 0; i < blocks.length; i++) {
      long blockLength = blocks[i].getLength();
      targetBlockEnd = targetBlockStart + blockLength - 1;

      if (target >= targetBlockStart && target <= targetBlockEnd) {
        targetBlock = i;
        break;
      } else {
        targetBlockStart = targetBlockEnd + 1;
      }
    }
    if (targetBlock < 0) {
      throw new IOException(
          "Impossible situation: could not find target position " + target);
    }
    long offsetIntoBlock = target - targetBlockStart;

    // read block blocks[targetBlock] from position offsetIntoBlock

    File fileBlock = File.createTempFile("s3fs-in", "");
    fileBlock.deleteOnExit();
    InputStream in = store.getBlockStream(blocks[targetBlock], offsetIntoBlock);
    OutputStream out = new BufferedOutputStream(new FileOutputStream(fileBlock));
    byte[] buf = new byte[bufferSize];
    int numRead;
    while ((numRead = in.read(buf)) >= 0) {
      out.write(buf, 0, numRead);
    }
    out.close();
    in.close();

    this.pos = target;
    this.blockEnd = targetBlockEnd;
    this.blockStream = new DataInputStream(new FileInputStream(fileBlock));

  }

  @Override
  public void close() throws IOException {
    if (closed) {
      throw new IOException("Stream closed");
    }
    if (blockStream != null) {
      blockStream.close();
      blockStream.close();
      blockStream = null;
    }
    super.close();
    closed = true;
  }

  /**
   * We don't support marks.
   */
  @Override
  public boolean markSupported() {
    return false;
  }

  @Override
  public void mark(int readLimit) {
    // Do nothing
  }

  @Override
  public void reset() throws IOException {
    throw new IOException("Mark not supported");
  }

}
