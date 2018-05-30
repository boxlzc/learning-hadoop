package org.apache.hadoop.fs.s3;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSInputStream;
import org.apache.hadoop.fs.FSOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.Progressable;

/**
 * <p>
 * A {@link FileSystem} backed by <a href="http://aws.amazon.com/s3">Amazon S3</a>.
 * </p>
 * @author Tom White
 */
public class S3FileSystem extends FileSystem {

  private static final long DEFAULT_BLOCK_SIZE = 1 * 1024 * 1024;
  
  private URI uri;

  private FileSystemStore store;

  private FileSystem localFs;

  private Path workingDir = new Path("/user", System.getProperty("user.name"));

  public S3FileSystem() {
    this(new Jets3tFileSystemStore());
  }

  public S3FileSystem(FileSystemStore store) {
    this.store = store;
  }

  @Override
  public URI getUri() {
    return uri;
  }

  @Override
  public void initialize(URI uri, Configuration conf) throws IOException {
    store.initialize(uri, conf);
    setConf(conf);
    this.uri = URI.create(uri.getScheme() + "://" + uri.getAuthority());    
    this.localFs = get(URI.create("file:///"), conf);
  }  

  @Override
  public String getName() {
    return getUri().toString();
  }

  @Override
  public Path getWorkingDirectory() {
    return workingDir;
  }

  @Override
  public void setWorkingDirectory(Path dir) {
    workingDir = makeAbsolute(dir);
  }

  private Path makeAbsolute(Path path) {
    if (path.isAbsolute()) {
      return path;
    }
    return new Path(workingDir, path);
  }

  @Override
  public boolean exists(Path path) throws IOException {
    return store.inodeExists(makeAbsolute(path));
  }

  @Override
  public boolean mkdirs(Path path) throws IOException {
    Path absolutePath = makeAbsolute(path);
    INode inode = store.getINode(absolutePath);
    if (inode == null) {
      store.storeINode(absolutePath, INode.DIRECTORY_INODE);
    } else if (inode.isFile()) {
      throw new IOException(String.format(
          "Can't make directory for path %s since it is a file.", absolutePath));
    }
    Path parent = absolutePath.getParent();
    return (parent == null || mkdirs(parent));
  }

  @Override
  public boolean isDirectory(Path path) throws IOException {
    INode inode = store.getINode(makeAbsolute(path));
    if (inode == null) {
      return false;
    }
    return inode.isDirectory();
  }

  @Override
  public boolean isFile(Path path) throws IOException {
    INode inode = store.getINode(makeAbsolute(path));
    if (inode == null) {
      return false;
    }
    return inode.isFile();
  }

  private INode checkFile(Path path) throws IOException {
    INode inode = store.getINode(makeAbsolute(path));
    if (inode == null) {
      throw new IOException("No such file.");
    }
    if (inode.isDirectory()) {
      throw new IOException("Path " + path + " is a directory.");
    }
    return inode;
  }

  @Override
  public Path[] listPathsRaw(Path path) throws IOException {
    Path absolutePath = makeAbsolute(path);
    INode inode = store.getINode(absolutePath);
    if (inode == null) {
      return null;
    } else if (inode.isFile()) {
      return new Path[] { absolutePath };
    } else { // directory
      Set<Path> paths = store.listSubPaths(absolutePath);
      return paths.toArray(new Path[0]);
    }
  }

  @Override
  public FSOutputStream createRaw(Path file, boolean overwrite,
      short replication, long blockSize) throws IOException {

    return createRaw(file, overwrite, replication, blockSize, null);
  }

  @Override
  public FSOutputStream createRaw(Path file, boolean overwrite,
      short replication, long blockSize, Progressable progress)
      throws IOException {

    INode inode = store.getINode(makeAbsolute(file));
    if (inode != null) {
      if (overwrite) {
        deleteRaw(file);
      } else {
        throw new IOException("File already exists: " + file);
      }
    } else {
      Path parent = file.getParent();
      if (parent != null) {
        if (!mkdirs(parent)) {
          throw new IOException("Mkdirs failed to create " + parent.toString());
        }
      }      
    }
    return new S3OutputStream(getConf(), store, makeAbsolute(file),
        blockSize, progress);
  }

  @Override
  public FSInputStream openRaw(Path path) throws IOException {
    INode inode = checkFile(path);
    return new S3InputStream(getConf(), store, inode);
  }

  @Override
  public boolean renameRaw(Path src, Path dst) throws IOException {
    // TODO: Check corner cases: dst already exists,
    // or if path is directory with children
    Path absoluteSrc = makeAbsolute(src);
    INode inode = store.getINode(absoluteSrc);
    if (inode == null) {
      throw new IOException("No such file.");
    }
    store.storeINode(makeAbsolute(dst), inode);
    store.deleteINode(absoluteSrc);
    return true;
  }

  @Override
  public boolean deleteRaw(Path path) throws IOException {
    Path absolutePath = makeAbsolute(path);
    INode inode = store.getINode(absolutePath);
    if (inode == null) {
      return false;
    }
    if (inode.isFile()) {
      store.deleteINode(absolutePath);
      for (Block block : inode.getBlocks()) {
        store.deleteBlock(block);
      }
    } else {
      Path[] contents = listPathsRaw(absolutePath);
      if (contents == null) {
        return false;
      }
      for (Path p : contents) {
        if (! deleteRaw(p)) {
          return false;
        }
      }
      store.deleteINode(absolutePath);
    }
    return true;
  }

  @Override
  public long getLength(Path path) throws IOException {
    INode inode = checkFile(path);
    long length = 0;
    for (Block block : inode.getBlocks()) {
      length += block.getLength();
    }
    return length;
  }

  /**
   * Replication is not supported for S3 file systems since S3 handles it for
   * us.
   */
  @Override
  public short getReplication(Path path) throws IOException {
    return 1;
  }

  @Override
  public short getDefaultReplication() {
    return 1;
  }

  /**
   * Replication is not supported for S3 file systems since S3 handles it for
   * us.
   */
  @Override
  public boolean setReplicationRaw(Path path, short replication)
      throws IOException {
    return true;
  }

  @Override
  public long getBlockSize(Path path) throws IOException {
    INode inode = store.getINode(makeAbsolute(path));
    if (inode == null) {
      throw new IOException("No such file or directory.");
    }
    Block[] blocks = inode.getBlocks();
    if (blocks == null || blocks.length == 0) {
      return 0;
    }
    return blocks[0].getLength();
  }

  @Override
  public long getDefaultBlockSize() {
    return getConf().getLong("fs.s3.block.size", DEFAULT_BLOCK_SIZE);
  }

  /**
   * Return 1x1 'localhost' cell if the file exists. Return null if otherwise.
   */
  @Override
  public String[][] getFileCacheHints(Path f, long start, long len)
      throws IOException {
    // TODO: Check this is the correct behavior
    if (!exists(f)) {
      return null;
    }
    return new String[][] { { "localhost" } };
  }

  @Override
  public void lock(Path path, boolean shared) throws IOException {
    // TODO: Design and implement
  }

  @Override
  public void release(Path path) throws IOException {
    // TODO: Design and implement
  }

  @Override
  public void reportChecksumFailure(Path path, FSInputStream in,
      long start, long length, int crc) {
    // TODO: What to do here?
  }

  @Override
  public void moveFromLocalFile(Path src, Path dst) throws IOException {
    FileUtil.copy(localFs, src, this, dst, true, getConf());
  }

  @Override
  public void copyFromLocalFile(Path src, Path dst) throws IOException {
    FileUtil.copy(localFs, src, this, dst, false, true, getConf());
  }

  @Override
  public void copyToLocalFile(Path src, Path dst, boolean copyCrc) throws IOException {
    FileUtil.copy(this, src, localFs, dst, false, copyCrc, getConf());
  }

  @Override
  public Path startLocalOutput(Path fsOutputFile, Path tmpLocalFile)
      throws IOException {
    return tmpLocalFile;
  }

  @Override
  public void completeLocalOutput(Path fsOutputFile, Path tmpLocalFile)
      throws IOException {
    moveFromLocalFile(tmpLocalFile, fsOutputFile);
  }

  // diagnostic methods

  void dump() throws IOException {
    store.dump();
  }

  void purge() throws IOException {
    store.purge();
  }

}
