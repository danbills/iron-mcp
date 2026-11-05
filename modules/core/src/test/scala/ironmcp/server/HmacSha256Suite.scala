package ironmcp
package server

import java.nio.charset.StandardCharsets.UTF_8

/** FIPS 180-4 example digests and the RFC 4231 HMAC-SHA256 test vectors. */
class HmacSha256Suite extends munit.FunSuite:

  private def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString
  private def bytes(text: String): Array[Byte] = text.getBytes(UTF_8)

  test("SHA-256 of the empty string"):
    assertEquals(hex(HmacSha256.sha256(Array.emptyByteArray)), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

  test("SHA-256 of 'abc'"):
    assertEquals(hex(HmacSha256.sha256(bytes("abc"))), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

  test("SHA-256 of a two-block message"):
    assertEquals(
      hex(HmacSha256.sha256(bytes("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))),
      "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
    )

  test("SHA-256 of a million 'a's"):
    assertEquals(
      hex(HmacSha256.sha256(Array.fill(1000000)('a'.toByte))),
      "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"
    )

  test("RFC 4231 test case 1"):
    assertEquals(
      hex(HmacSha256.hmac(Array.fill(20)(0x0b.toByte), bytes("Hi There"))),
      "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
    )

  test("RFC 4231 test case 2"):
    assertEquals(
      hex(HmacSha256.hmac(bytes("Jefe"), bytes("what do ya want for nothing?"))),
      "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
    )

  test("RFC 4231 test case 6: a key longer than the block is hashed first"):
    assertEquals(
      hex(HmacSha256.hmac(Array.fill(131)(0xaa.toByte), bytes("Test Using Larger Than Block-Size Key - Hash Key First"))),
      "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54"
    )

  test("constant-time comparison"):
    assert(HmacSha256.constantTimeEquals(bytes("same"), bytes("same")))
    assert(!HmacSha256.constantTimeEquals(bytes("same"), bytes("sane")))
    assert(!HmacSha256.constantTimeEquals(bytes("same"), bytes("sam")))
