package ironmcp
package server

/** SHA-256 (FIPS 180-4) and HMAC-SHA256 (RFC 2104) in plain Scala.
  *
  * Core cross-builds to Scala Native, whose javalib has neither
  * `java.security.MessageDigest` nor `javax.crypto`, so the one primitive core
  * needs (sealing `requestState`) is written out here. `HmacSha256Suite` checks it
  * against the RFC 4231 test vectors.
  */
private[server] object HmacSha256:

  private val K: Array[Int] = Array(
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
  )

  def sha256(message: Array[Byte]): Array[Byte] =
    val h = Array(0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19)
    // Pad: 0x80, zeros, then the bit length as a 64-bit big-endian integer.
    val bitLength = message.length.toLong * 8
    val padded    = new Array[Byte](((message.length + 8) / 64 + 1) * 64)
    System.arraycopy(message, 0, padded, 0, message.length)
    padded(message.length) = 0x80.toByte
    for i <- 0 until 8 do padded(padded.length - 1 - i) = (bitLength >>> (8 * i)).toByte

    val w = new Array[Int](64)
    for block <- 0 until padded.length / 64 do
      for t <- 0 until 16 do
        val o = block * 64 + t * 4
        w(t) = ((padded(o) & 0xff) << 24) | ((padded(o + 1) & 0xff) << 16) | ((padded(o + 2) & 0xff) << 8) |
          (padded(o + 3) & 0xff)
      for t <- 16 until 64 do
        val s0 = Integer.rotateRight(w(t - 15), 7) ^ Integer.rotateRight(w(t - 15), 18) ^ (w(t - 15) >>> 3)
        val s1 = Integer.rotateRight(w(t - 2), 17) ^ Integer.rotateRight(w(t - 2), 19) ^ (w(t - 2) >>> 10)
        w(t) = w(t - 16) + s0 + w(t - 7) + s1

      var a = h(0); var b = h(1); var c = h(2); var d = h(3)
      var e = h(4); var f = h(5); var g = h(6); var hh = h(7)
      for t <- 0 until 64 do
        val s1    = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25)
        val ch    = (e & f) ^ (~e & g)
        val temp1 = hh + s1 + ch + K(t) + w(t)
        val s0    = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22)
        val maj   = (a & b) ^ (a & c) ^ (b & c)
        val temp2 = s0 + maj
        hh = g; g = f; f = e; e = d + temp1
        d = c; c = b; b = a; a = temp1 + temp2
      h(0) += a; h(1) += b; h(2) += c; h(3) += d
      h(4) += e; h(5) += f; h(6) += g; h(7) += hh

    h.flatMap(word => Array((word >>> 24).toByte, (word >>> 16).toByte, (word >>> 8).toByte, word.toByte))

  def hmac(key: Array[Byte], message: Array[Byte]): Array[Byte] =
    val blockKey = new Array[Byte](64)
    val shortKey = if key.length > 64 then sha256(key) else key
    System.arraycopy(shortKey, 0, blockKey, 0, shortKey.length)
    val inner = blockKey.map(byte => (byte ^ 0x36).toByte)
    val outer = blockKey.map(byte => (byte ^ 0x5c).toByte)
    sha256(outer ++ sha256(inner ++ message))

  /** Compares in time independent of where the inputs first differ. */
  def constantTimeEquals(left: Array[Byte], right: Array[Byte]): Boolean =
    left.length == right.length && left.indices.foldLeft(0)((acc, i) => acc | (left(i) ^ right(i))) == 0
