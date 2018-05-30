package org.apache.hadoop.fs.s3;

class Block {
  private long id;

  private long length;

  public Block(long id, long length) {
    this.id = id;
    this.length = length;
  }

  public long getId() {
    return id;
  }

  public long getLength() {
    return length;
  }

  @Override
  public String toString() {
    return "Block[" + id + ", " + length + "]";
  }

}
