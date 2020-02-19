/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.execution

import java.io._
import java.nio.file.{Path, StandardOpenOption, Files}
import java.util.{ArrayList => JavaArrayList}

import org.apache.spark.SparkException
import org.apache.spark.sql.catalyst.expressions.{Projection, Row}
import org.apache.spark.sql.execution.CS143Utils._

import scala.collection.JavaConverters._

/**
  * This trait represents a regular relation that is hash partitioned and spilled to
  * disk.
  */
private[sql] sealed trait DiskHashedRelation {
  /**
    *
    * @return an iterator of the [[DiskPartition]]s that make up this relation.
    */
  def getIterator(): Iterator[DiskPartition]

  /**
    * Close all the partitions for this relation. This should involve deleting the files hashed into.
    */
  def closeAllPartitions()
}

/**
  * A general implementation of [[DiskHashedRelation]].
  *
  * @param partitions the disk partitions that we are going to spill to
  */
protected [sql] final class GeneralDiskHashedRelation(partitions: Array[DiskPartition])
  extends DiskHashedRelation with Serializable {

  override def getIterator() = {
    /* IMPLEMENT THIS METHOD */
    partitions.iterator
  }

  override def closeAllPartitions() = {
    /* IMPLEMENT THIS METHOD */
    for(idx <- 0 until partitions.size){
      partitions(idx).closePartition()
    }
  }
}

private[sql] class DiskPartition (
                                   filename: String,
                                   blockSize: Int) {
  private val path: Path = Files.createTempFile("", filename)
  private val data: JavaArrayList[Row] = new JavaArrayList[Row]
  private val outStream: OutputStream = Files.newOutputStream(path)
  private val inStream: InputStream = Files.newInputStream(path)
  private val chunkSizes: JavaArrayList[Int] = new JavaArrayList[Int]()
  private var writtenToDisk: Boolean = false
  private var inputClosed: Boolean = false

  /**
    * This method inserts a new row into this particular partition. If the size of the partition
    * exceeds the blockSize, the partition is spilled to disk.
    *
    * @param row the [[Row]] we are adding
    */
  def insert(row: Row) = {
    /* IMPLEMENT THIS METHOD */
    if(inputClosed == false) {// check if you can insert or not
      if (measurePartitionSize() > blockSize) { // if you need to overflow to disk then do so and then clar the data
        spillPartitionToDisk()
        data.clear()
      }
      data.add(row) // else we just add the row as standard using the JavaArrayList add function
    }
    else {
      throw new SparkException("Input is closed, cannot insert.")
    }
  }

  /**
    * This method converts the data to a byte array and returns the size of the byte array
    * as an estimation of the size of the partition.
    *
    * @return the estimated size of the data
    */
  private[this] def measurePartitionSize(): Int = {
    CS143Utils.getBytesFromList(data).size
  }

  /**
    * Uses the [[Files]] API to write a byte array representing data to a file.
    */
  private[this] def spillPartitionToDisk() = {
    val bytes: Array[Byte] = getBytesFromList(data)

    // This array list stores the sizes of chunks written in order to read them back correctly.
    chunkSizes.add(bytes.size)

    Files.write(path, bytes, StandardOpenOption.APPEND)
    writtenToDisk = true
  }

  /**
    * If this partition has been closed, this method returns an Iterator of all the
    * data that was written to disk by this partition.
    *
    * @return the [[Iterator]] of the data
    */
  def getData(): Iterator[Row] = {
    if (!inputClosed) {
      throw new SparkException("Should not be reading from file before closing input. Bad things will happen!")
    }

    new Iterator[Row] {
      var currentIterator: Iterator[Row] = data.iterator.asScala
      val chunkSizeIterator: Iterator[Int] = chunkSizes.iterator().asScala
      var byteArray: Array[Byte] = null

      override def next() = {
        /* IMPLEMENT THIS METHOD */
        if (currentIterator.hasNext) { // check if our current iterator as more
          currentIterator.next() // if we do then return whatever it is
        } else {
          throw new NoSuchElementException("no more data") // if not then error out
        }
      }

      override def hasNext() = {
        /* IMPLEMENT THIS METHOD */
        if (currentIterator.hasNext || fetchNextChunk()) { // use shortcircuiting so that if we need to we can update our eterator
          true
        } else {
          false
        }
      }

      /**
        * Fetches the next chunk of the file and updates the iterator. Should return true
        * unless the iterator is empty.
        *
        * @return true unless the iterator is empty.
        */
      private[this] def fetchNextChunk(): Boolean = {
        /* IMPLEMENT THIS METHOD */
        if (chunkSizeIterator.hasNext == true) { // used to check if we can move on to the next chunk
          byteArray = CS143Utils.getNextChunkBytes(inStream,chunkSizeIterator.next(), byteArray)
          currentIterator = CS143Utils.getListFromBytes(byteArray).iterator.asScala
          true
        } else {
          false
        }
      }
    }
  }

  /**
    * Closes this partition, implying that no more data will be written to this partition. If getData()
    * is called without closing the partition, an error will be thrown.
    *
    * If any data has not been written to disk yet, it should be written. The output stream should
    * also be closed.
    */
  def closeInput() = {
    /* IMPLEMENT THIS METHOD */
    if (data.size() > 0) {  // check if there is any data left
      spillPartitionToDisk()  // if there is then send it to disk and then clear it from memory
      data.clear()
    }
    outStream.close() // close the outstream as specified
    inputClosed = true
  }


  /**
    * Closes this partition. This closes the input stream and deletes the file backing the partition.
    */
  private[sql] def closePartition() = {
    inStream.close()
    Files.deleteIfExists(path)
  }
}

private[sql] object DiskHashedRelation {

  /**
    * Given an input iterator, partitions each row into one of a number of [[DiskPartition]]s
    * and constructors a [[DiskHashedRelation]].
    *
    * This executes the first phase of external hashing -- using a course-grained hash function
    * to partition the tuples to disk.
    *
    * The block size is approximately set to 64k because that is a good estimate of the average
    * buffer page.
    *
    * @param input the input [[Iterator]] of [[Row]]s
    * @param keyGenerator a [[Projection]] that generates the keys for the input
    * @param size the number of [[DiskPartition]]s
    * @param blockSize the threshold at which each partition will spill
    * @return the constructed [[DiskHashedRelation]]
    */
  def apply (
              input: Iterator[Row],
              keyGenerator: Projection,
              size: Int = 64,
              blockSize: Int = 64000) = {
    /* IMPLEMENT THIS METHOD */
    var PartitionArr: Array[DiskPartition] = new Array[DiskPartition](size) // create a list of DiskPartitions
    for ( idx <- 0 until PartitionArr.size) { // iterate through all of partition arrays
      PartitionArr(idx) = new DiskPartition(filename = "disk_part" + idx.toString(), blockSize = blockSize) // initiate a new DiskPartition
    }
    while (input.hasNext) {// iterate through all of the input
      var next_row = input.next()
      var hashed_val = keyGenerator(next_row).hashCode() % size
      PartitionArr(hashed_val).insert(next_row) // insert the row at idx = hashed_val using that partitions insert function
    }
    for(idx <- 0 until PartitionArr.size){
      PartitionArr(idx).closeInput() // close all input for every partition
    }
    val ret = new GeneralDiskHashedRelation(PartitionArr)
    ret
  }
}
