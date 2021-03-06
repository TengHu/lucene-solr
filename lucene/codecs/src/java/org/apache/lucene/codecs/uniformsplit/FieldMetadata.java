/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.lucene.codecs.uniformsplit;

import java.io.IOException;

import org.apache.lucene.codecs.BlockTermState;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.store.DataInput;
import org.apache.lucene.store.DataOutput;
import org.apache.lucene.util.Accountable;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.RamUsageEstimator;

/**
 * Metadata and stats for one field in the index.
 * <p>
 * There is only one instance of {@link FieldMetadata} per {@link FieldInfo}.
 *
 * @lucene.experimental
 */
public class FieldMetadata implements Accountable {

  private static final long BASE_RAM_USAGE = RamUsageEstimator.shallowSizeOfInstance(FieldMetadata.class);

  protected final FieldInfo fieldInfo;
  protected final boolean isMutable;
  protected final FixedBitSet docsSeen;

  protected int sumDocFreq;
  protected int numTerms;
  protected int sumTotalTermFreq;
  protected int docCount;

  protected long dictionaryStartFP;
  protected long firstBlockStartFP;
  protected long lastBlockStartFP;

  protected BytesRef lastTerm;

  /**
   * Constructs a {@link FieldMetadata} used for writing the index. This {@link FieldMetadata} is mutable.
   *
   * @param maxDoc The total number of documents in the segment being written.
   */
  public FieldMetadata(FieldInfo fieldInfo, int maxDoc) {
    this(fieldInfo, maxDoc, true);
  }

  public FieldMetadata(FieldInfo fieldInfo, int maxDoc, boolean isMutable) {
    this(fieldInfo, maxDoc, isMutable, -1, -1, null);
  }

  /**
   * @param isMutable Set true if this FieldMetadata is created for writing the index. Set false if it is used for reading the index.
   */
  public FieldMetadata(FieldInfo fieldInfo, int maxDoc, boolean isMutable, long firstBlockStartFP, long lastBlockStartFP, BytesRef lastTerm) {
    assert isMutable || maxDoc == 0;
    this.fieldInfo = fieldInfo;
    this.isMutable = isMutable;
    // docsSeen must not be set if this FieldMetadata is immutable, that means it is used for reading the index.
    this.docsSeen = isMutable ? new FixedBitSet(maxDoc) : null;
    this.dictionaryStartFP = -1;
    this.firstBlockStartFP = firstBlockStartFP;
    this.lastBlockStartFP = lastBlockStartFP;
    this.lastTerm = lastTerm;
  }

  /**
   * Updates the field stats with the given {@link BlockTermState} for the current
   * block line (for one term).
   */
  public void updateStats(BlockTermState state) {
    assert isMutable;
    assert state.docFreq > 0;
    sumDocFreq += state.docFreq;
    if (state.totalTermFreq > 0) {
      sumTotalTermFreq += state.totalTermFreq;
    }
    numTerms++;
  }

  /**
   * Provides the {@link FixedBitSet} to keep track of the docs seen when calling
   * {@link org.apache.lucene.codecs.PostingsWriterBase#writeTerm(BytesRef, TermsEnum, FixedBitSet, org.apache.lucene.codecs.NormsProducer)}.
   * <p>
   * The returned {@link FixedBitSet} is created once in this {@link FieldMetadata}
   * constructor.
   *
   * @return The {@link FixedBitSet} for the docs seen, during segment writing;
   * or null if this {@link FieldMetadata} is created immutable during segment reading.
   */
  public FixedBitSet getDocsSeen() {
    return docsSeen;
  }

  public FieldInfo getFieldInfo() {
    return fieldInfo;
  }

  public int getSumDocFreq() {
    return sumDocFreq;
  }

  public int getNumTerms() {
    return numTerms;
  }

  public int getSumTotalTermFreq() {
    return sumTotalTermFreq;
  }

  public int getDocCount() {
    return isMutable ? docsSeen.cardinality() : docCount;
  }

  /**
   * @return The file pointer to the start of the first block of the field.
   */
  public long getFirstBlockStartFP() {
    return firstBlockStartFP;
  }

  /**
   * Sets the file pointer to the start of the first block of the field.
   */
  public void setFirstBlockStartFP(long firstBlockStartFP) {
    assert isMutable;
    this.firstBlockStartFP = firstBlockStartFP;
  }

  /**
   * @return The start file pointer for the last block of the field.
   */
  public long getLastBlockStartFP() {
    return lastBlockStartFP;
  }

  /**
   * Sets the file pointer after the end of the last block of the field.
   */
  public void setLastBlockStartFP(long lastBlockStartFP) {
    assert isMutable;
    this.lastBlockStartFP = lastBlockStartFP;
  }

  /**
   * @return The file pointer to the start of the dictionary of the field.
   */
  public long getDictionaryStartFP() {
    return dictionaryStartFP;
  }

  /**
   * Sets the file pointer to the start of the dictionary of the field.
   */
  public void setDictionaryStartFP(long dictionaryStartFP) {
    assert isMutable;
    this.dictionaryStartFP = dictionaryStartFP;
  }

  public void setLastTerm(BytesRef lastTerm) {
    assert lastTerm != null;
    this.lastTerm = lastTerm;
  }

  public BytesRef getLastTerm() {
    return lastTerm;
  }

  @Override
  public long ramBytesUsed() {
    return BASE_RAM_USAGE
        + (docsSeen == null ? 0 : docsSeen.ramBytesUsed());
  }

  public static FieldMetadata read(DataInput input, FieldInfos fieldInfos, int maxNumDocs) throws IOException {
    int fieldId = input.readVInt();
    FieldInfo fieldInfo = fieldInfos.fieldInfo(fieldId);
    if (fieldInfo == null) {
      throw new CorruptIndexException("Illegal field id= " + fieldId, input);
    }
    FieldMetadata fieldMetadata = new FieldMetadata(fieldInfo, 0, false);

    fieldMetadata.numTerms = input.readVInt();
    if (fieldMetadata.numTerms <= 0) {
      throw new CorruptIndexException("Illegal number of terms= " + fieldMetadata.numTerms + " for field= " + fieldId, input);
    }

    fieldMetadata.sumDocFreq = input.readVInt();
    fieldMetadata.sumTotalTermFreq = fieldMetadata.sumDocFreq;
    if (fieldMetadata.fieldInfo.getIndexOptions().compareTo(IndexOptions.DOCS_AND_FREQS) >= 0) {
      fieldMetadata.sumTotalTermFreq += input.readVInt();
      if (fieldMetadata.sumTotalTermFreq < fieldMetadata.sumDocFreq) {
        // #positions must be >= #postings.
        throw new CorruptIndexException("Illegal sumTotalTermFreq= " + fieldMetadata.sumTotalTermFreq
            + " sumDocFreq= " + fieldMetadata.sumDocFreq + " for field= " + fieldId, input);
      }
    }

    fieldMetadata.docCount = input.readVInt();
    if (fieldMetadata.docCount < 0 || fieldMetadata.docCount > maxNumDocs) {
      // #docs with field must be <= #docs.
      throw new CorruptIndexException("Illegal number of docs= " + fieldMetadata.docCount
          + " maxNumDocs= " + maxNumDocs + " for field=" + fieldId, input);
    }
    if (fieldMetadata.sumDocFreq < fieldMetadata.docCount) {
      // #postings must be >= #docs with field.
      throw new CorruptIndexException("Illegal sumDocFreq= " + fieldMetadata.sumDocFreq
          + " docCount= " + fieldMetadata.docCount + " for field= " + fieldId, input);
    }

    fieldMetadata.dictionaryStartFP = input.readVLong();
    fieldMetadata.firstBlockStartFP = input.readVLong();
    fieldMetadata.lastBlockStartFP = input.readVLong();

    int lastTermLength = input.readVInt();
    BytesRef lastTerm = new BytesRef(lastTermLength);
    if (lastTermLength > 0) {
      input.readBytes(lastTerm.bytes, 0, lastTermLength);
      lastTerm.length = lastTermLength;
    } else if (lastTermLength < 0) {
      throw new CorruptIndexException("Illegal last term length= " + lastTermLength + " for field= " + fieldId, input);
    }
    fieldMetadata.setLastTerm(lastTerm);

    return fieldMetadata;
  }

  public void write(DataOutput output) throws IOException {
    assert dictionaryStartFP >= 0;
    assert firstBlockStartFP >= 0;
    assert lastBlockStartFP >= 0;
    assert numTerms > 0 : "There should be at least one term for field " + fieldInfo.name + ": " + numTerms;
    assert firstBlockStartFP <= lastBlockStartFP : "start: " + firstBlockStartFP + " end: " + lastBlockStartFP;
    assert lastTerm != null : "you must set the last term";

    output.writeVInt(fieldInfo.number);

    output.writeVInt(numTerms);
    output.writeVInt(sumDocFreq);

    if (fieldInfo.getIndexOptions().compareTo(IndexOptions.DOCS_AND_FREQS) >= 0) {
      assert sumTotalTermFreq >= sumDocFreq : "sumTotalFQ: " + sumTotalTermFreq + " sumDocFQ: " + sumDocFreq;
      output.writeVInt(sumTotalTermFreq - sumDocFreq);
    }

    output.writeVInt(getDocCount());

    output.writeVLong(dictionaryStartFP);
    output.writeVLong(firstBlockStartFP);
    output.writeVLong(lastBlockStartFP);

    if (lastTerm.length > 0) {
      output.writeVInt(lastTerm.length);
      output.writeBytes(lastTerm.bytes, lastTerm.offset, lastTerm.length);
    } else {
      output.writeVInt(0);
    }
  }
}