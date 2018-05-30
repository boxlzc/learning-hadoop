package org.apache.hadoop.fs.s3;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSOutputStream;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.s3.INode.FileType;
import org.apache.hadoop.util.Progressable;

class S3OutputStream extends FSOutputStream {

  private int bufferSize;

  private FileSystemStore store;

  private Path path;

  private long blockSize;

  private File backupFile;

  private OutputStream backupStream;

  private Random r = new Random();

  private boolean closed;

  private int pos = 0;

  private long filePos = 0;

  private int bytesWrittenToBlock = 0;

  private byte[] outBuf;

  private List<Block> blocks = new ArrayList<Block>();

  private Block nextBlock;

  public S3OutputStream(Configuration conf, FileSystemStore store,
      Path path, long blockSize, Progressable progress) throws IOException {
    
    this.store = store;
    this.path = path;
    this.blockSize = blockSize;
    this.backupFile = newBackupFile();
    this.backupStream = new FileOutputStream(backupFile);
    this.bufferSize = conf.getInt("io.file.buffer.size", 4096);
    this.outBuf = new byte[bufferSize];

  }

  private File newBackupFile() throws IOException {
    File result = File.createTempFile("s3fs-out", "");
    result.deleteOnExit();
    return result;
  }

  @Override
  public long getPos() throws IOException {
    return filePos;
  }

  @Override
  public synchronized void write(int b) throws IOException {
    if (closed) {
      throw new IOException("Stream closed");
    }

    if ((bytesWrittenToBlock + pos == blockSize) || (pos >= bufferSize)) {
      flush();
    }
    outBuf[pos++] = (byte) b;
    filePos++;
  }

  @Override
  public synchronized void write(byte b[], int off, int len) throws IOException {
    if (closed) {
      throw new IOException("Stream closed");
    }
    while (len > 0) {
      int remaining = bufferSize - pos;
      int toWrite = Math.min(remaining, len);
      System.arraycopy(b, off, outBuf, pos, toWrite);
      pos += toWrite;
      off += toWrite;
      len -= toWrite;
      filePos += toWrite;

      if ((bytesWrittenToBlock + pos >= blockSize) || (pos == bufferSize)) {
        flush();
      }
    }
  }

  @Override
  public synchronized void flush() throws IOException {
    if (closed) {
      throw new IOException("Stream closed");
    }

    if (bytesWrittenToBlock + pos >= blockSize) {
      flushData((int) blockSize - bytesWrittenToBlock);
    }
    if (bytesWrittenToBlock == blockSize) {
      endBlock();
    }
    flushData(pos);
  }

  private synchronized void flushData(int maxPos) throws IOException {
    int workingPos = Math.min(pos, maxPos);

    if (workingPos > 0) {
      //
      // To the local block backup, write just the bytes
      //
      backupStream.write(outBuf, 0, workingPos);

      //
      // Track position
      //
      bytesWrittenToBlock += workingPos;
      System.arraycopy(outBuf, workingPos, outBuf, 0, pos - workingPos);
      pos -= workingPos;
    }
  }

  private synchronized void endBlock() throws IOException {
    //
    // Done with local copy
    //
    backupStream.close();

    //
    // Send it to S3
    //
    // TODO: Use passed in Progressable to report progress.
    nextBlockOutputStream();
    InputStream in = new FileInputStream(backupFile);
    store.storeBlock(nextBlock, in);
    in.close();
    internalClose();

    //
    // Delete local backup, start new one
    //
    backupFile.delete();
    backupFile = newBackupFile();
    backupStream = new FileOutputStream(backupFile);
    bytesWrittenToBlock = 0;
  }

  private synchronized void nextBlockOutputStream() throws IOException {
    long blockId = r.nextLong();
    while (store.blockExists(blockId)) {
      blockId = r.nextLong();
    }
    nextBlock = new Block(blockId, bytesWrittenToBlock);
    blocks.add(nextBlock);
    bytesWrittenToBlock = 0;
  }

  private synchronized void internalClose() throws IOException {
    INode inode = new INode(FileType.FILE, blocks.toArray(new Block[blocks
        .size()]));
    store.storeINode(path, inode);
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) {
      throw new IOException("Stream closed");
    }

    flush();
    if (filePos == 0 || bytesWrittenToBlock != 0) {
      endBlock();
    }

    backupStream.close();
    backupFile.delete();

    super.close();

    closed = true;
  }

}
