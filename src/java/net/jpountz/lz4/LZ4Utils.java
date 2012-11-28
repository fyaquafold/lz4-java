package net.jpountz.lz4;

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

import static net.jpountz.util.Utils.checkRange;
import static net.jpountz.util.Utils.readInt;

enum LZ4Utils {
  ;

  static final int MEMORY_USAGE = 14;
  static final int NOT_COMPRESSIBLE_DETECTION_LEVEL = 6;

  static final int MIN_MATCH = 4;

  static final int HASH_LOG = MEMORY_USAGE - 2;
  static final int HASH_TABLE_SIZE = 1 << HASH_LOG;

  static final int SKIP_STRENGTH = Math.max(NOT_COMPRESSIBLE_DETECTION_LEVEL, 2);
  static final int COPY_LENGTH = 8;
  static final int LAST_LITERALS = 5;
  static final int MF_LIMIT = COPY_LENGTH + MIN_MATCH;
  static final int MIN_LENGTH = MF_LIMIT + 1;

  static final int MAX_DISTANCE = 1 << 16;

  static final int ML_BITS = 4;
  static final int ML_MASK = (1 << ML_BITS) - 1;
  static final int RUN_BITS = 8 - ML_BITS;
  static final int RUN_MASK = (1 << RUN_BITS) - 1;

  static final int LZ4_64K_LIMIT = (1 << 16) + (MF_LIMIT - 1);
  static final int HASH_LOG_64K = HASH_LOG + 1;
  static final int HASH_TABLE_SIZE_64K = 1 << HASH_LOG_64K;

  static final int HASH_LOG_HC = 15;
  static final int HASH_TABLE_SIZE_HC = 1 << HASH_LOG_HC;
  static final int OPTIMAL_ML = ML_MASK - 1 + MIN_MATCH;

  static final int maxCompressedLength(int length) {
    if (length < 0) {
      throw new IllegalArgumentException("length must be >= 0, got " + length);
    }
    return length + length / 255 + 16;
  }

  static int hash(int i) {
    return (i * -1640531535) >>> ((MIN_MATCH * 8) - HASH_LOG);
  }

  static int hash64k(int i) {
    return (i * -1640531535) >>> ((MIN_MATCH * 8) - HASH_LOG_64K);
  }

  static int hashHC(int i) {
    return (i * -1640531535) >>> ((MIN_MATCH * 8) - HASH_LOG_HC);
  }

  static int readShortLittleEndian(byte[] buf, int i) {
    return (buf[i] & 0xFF) | ((buf[i+1] & 0xFF) << 8);
  }

  static int hash(byte[] buf, int i) {
    return hash(readInt(buf, i));
  }

  static int hash64k(byte[] buf, int i) {
    return hash64k(readInt(buf, i));
  }

  static boolean readIntEquals(byte[] buf, int i, int j) {
    return buf[i] == buf[j] && buf[i+1] == buf[j+1] && buf[i+2] == buf[j+2] && buf[i+3] == buf[j+3];
  }

  static void naiveIncrementalCopy(byte[] dest, int matchOff, int dOff, int matchLen) {
    for (int i = 0; i < matchLen; ++i) {
      dest[dOff++] = dest[matchOff++];
    }
  }

  static void safeIncrementalCopy(byte[] dest, int matchOff, int dOff, int matchLen) {
    if (dOff - matchOff >= matchLen) {
      safeArraycopy(dest, matchOff, dest, dOff, matchLen);
    } else {
      naiveIncrementalCopy(dest, matchOff, dOff, matchLen);
    }
  }

  static void wildIncrementalCopy(byte[] dest, int matchOff, int dOff, int matchLen) {
    if (dOff - matchOff >= matchLen) {
      wildArraycopy(dest, matchOff, dest, dOff, matchLen);
    } else {
      naiveIncrementalCopy(dest, matchOff, dOff, matchLen);
    }
  }

  static int commonBytes(byte[] b, int o1, int o2, int limit) {
    int count = 0;
    while (o2 < limit && b[o1++] == b[o2++]) {
      ++count;
    }
    return count;
  }

  static int commonBytesBackward(byte[] b, int o1, int o2, int l1, int l2) {
    int count = 0;
    while (o1 > l1 && o2 > l2 && b[--o1] == b[--o2]) {
      ++count;
    }
    return count;
  }

  static void safeArraycopy(byte[] src, int sOff, byte[] dest, int dOff, int len) {
    System.arraycopy(src, sOff, dest, dOff, len);
  }

  static void wildArraycopy(byte[] src, int sOff, byte[] dest, int dOff, int len) {
    // can make decompression 10% faster
    final int fastLen = ((len - 1) & 0xFFFFFFF8) + COPY_LENGTH;
    try {
      System.arraycopy(src, sOff, dest, dOff, fastLen);
    } catch (ArrayIndexOutOfBoundsException e) {
      throw new LZ4Exception("Malformed input at " + sOff);
    }
  }

  static int encodeSequence(byte[] src, int anchor, int matchOff, int matchRef, int matchLen, byte[] dest, int dOff, int destEnd) {
    final int runLen = matchOff - anchor;
    final int tokenOff = dOff++;

    if (dOff + runLen + (2 + 1 + LAST_LITERALS) + (runLen >>> 8) > destEnd) {
      throw new LZ4Exception("maxDestLen is too small");
    }

    int token;
    if (runLen >= RUN_MASK) {
      token = (byte) (RUN_MASK << ML_BITS);
      dOff = writeLen(runLen - RUN_MASK, dest, dOff);
    } else {
      token = runLen << ML_BITS;
    }

    // copy literals
    wildArraycopy(src, anchor, dest, dOff, runLen);
    dOff += runLen;

    // encode offset
    final int matchDec = matchOff - matchRef;
    dest[dOff++] = (byte) matchDec;
    dest[dOff++] = (byte) (matchDec >>> 8);

    // encode match len
    matchLen -= 4;
    if (dOff + (1 + LAST_LITERALS) + (matchLen >>> 8) > destEnd) {
      throw new LZ4Exception("maxDestLen is too small");
    }
    if (matchLen >= ML_MASK) {
      token |= ML_MASK;
      dOff = writeLen(matchLen - RUN_MASK, dest, dOff);
    } else {
      token |= matchLen;
    }

    dest[tokenOff] = (byte) token;

    return dOff;
  }

  static int lastLiterals(byte[] src, int sOff, int srcLen, byte[] dest, int dOff, int destEnd) {
    final int runLen = srcLen;

    if (dOff + runLen + 1 + (runLen + 255 - RUN_MASK) / 255 > destEnd) {
      throw new LZ4Exception("maxDestLen is too small");
    }

    if (runLen >= RUN_MASK) {
      dest[dOff++] = (byte) (RUN_MASK << ML_BITS);
      dOff = writeLen(runLen - RUN_MASK, dest, dOff);
    } else {
      dest[dOff++] = (byte) (runLen << ML_BITS);
    }
    // copy literals
    System.arraycopy(src, sOff, dest, dOff, runLen);
    dOff += runLen;

    return dOff;
  }

  static int vIntLength(int n) {
    if (n < 0) {
      throw new IllegalArgumentException("Cannot encode negative integers");
    }
    int len = 0;
    do {
      ++len;
      n >>>= 7;
    } while (n > 0);
    return len;
  }

  static int readVInt(byte[] buf, int off, int len) {
    checkRange(buf, off, len);
    int n = 0;
    for (int i = 0; i < 4; ++i) {
      if (i >= len) {
        throw new LZ4Exception("Malformed stream");
      }
      final byte next = buf[off + i];
      n |= (next & 0x7F) << (7 * i);
      if (next >= 0) {
        return n;
      }
    }
    if (4 >= len) {
      throw new LZ4Exception("Malformed stream");
    }
    final byte next = buf[off + 4];
    if (next < 0 || next >= 1 << 5) {
      throw new LZ4Exception("Malformed stream");
    }
    n |= next << (7 * 4);
    return n;
  }

  static int writeVInt(int n, byte[] buf, int off, int len) {
    if (n < 0) {
      throw new IllegalArgumentException("Cannot encode negative integers");
    }
    int i;
    for (i = 0; (n & ~0x7F) != 0; n >>>= 7, ++i) {
      if (i >= len) {
        throw new LZ4Exception("Destination buffer is too small");
      }
      buf[off + i] = (byte) ((n & 0x7F) | 0x80);
    }
    if (i >= len) {
      throw new LZ4Exception("Destination buffer is too small");
    }
    buf[off + i] = (byte) n;
    return i + 1;
  }

  static int writeLen(int len, byte[] dest, int dOff) {
    while (len >= 0xFF) {
      dest[dOff++] = (byte) 0xFF;
      len -= 0xFF;
    }
    dest[dOff++] = (byte) len;
    return dOff;
  }

  static class Match {
    int start, ref, len;

    void fix(int correction) {
      start += correction;
      ref += correction;
      len -= correction;
    }

    int end() {
      return start + len;
    }
  }

  static void copyTo(Match m1, Match m2) {
    m2.len = m1.len;
    m2.start = m1.start;
    m2.ref = m1.ref;
  }

}
