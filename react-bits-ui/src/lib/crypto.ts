/**
 * 前端加密解密工具
 * 使用 AES-256-GCM 加密 + Gzip 压缩
 */

const ALGORITHM = 'AES-GCM'
const GCM_IV_LENGTH = 12 // 96 bits
const GCM_TAG_LENGTH = 16 // 128 bits

// 从密码派生密钥
async function deriveKey(password: string): Promise<CryptoKey> {
  const encoder = new TextEncoder()
  const passwordData = encoder.encode(password)

  // 使用 SHA-256 派生密钥
  const hashBuffer = await crypto.subtle.digest('SHA-256', passwordData)

  return await crypto.subtle.importKey(
    'raw',
    hashBuffer,
    { name: ALGORITHM },
    false,
    ['encrypt', 'decrypt']
  )
}

/**
 * 加密并压缩数据
 */
export async function encryptAndCompress(
  plaintext: string,
  secretKey: string
): Promise<string> {
  if (!plaintext) return plaintext

  try {
    const key = await deriveKey(secretKey)
    const encoder = new TextEncoder()
    const data = encoder.encode(plaintext)

    // 1. Gzip 压缩
    const compressed = await gzipCompress(data)

    // 2. 如果压缩后更大，使用原始数据
    const dataToEncrypt = compressed.length < data.length * 0.9 ? compressed : data

    // 3. 生成随机 IV
    const iv = crypto.getRandomValues(new Uint8Array(GCM_IV_LENGTH))

    // 4. AES-GCM 加密
    const encrypted = await crypto.subtle.encrypt(
      { name: ALGORITHM, iv, tagLength: GCM_TAG_LENGTH * 8 },
      key,
      dataToEncrypt
    )

    // 5. 组合 IV + 密文
    const result = new Uint8Array(iv.length + encrypted.byteLength)
    result.set(iv)
    result.set(new Uint8Array(encrypted), iv.length)

    // 6. Base64 编码
    return btoa(String.fromCharCode(...result))
  } catch (e) {
    console.warn('Encryption failed:', e)
    return plaintext
  }
}

/**
 * 解密并解压数据
 */
export async function decryptAndDecompress(
  ciphertext: string,
  secretKey: string
): Promise<string> {
  if (!ciphertext) return ciphertext

  // 如果不是 Base64 格式，直接返回
  if (!isBase64(ciphertext)) {
    return ciphertext
  }

  try {
    const key = await deriveKey(secretKey)

    // 1. Base64 解码
    const encrypted = Uint8Array.from(atob(ciphertext), c => c.charCodeAt(0))

    // 2. 提取 IV 和密文
    const iv = encrypted.slice(0, GCM_IV_LENGTH)
    const data = encrypted.slice(GCM_IV_LENGTH)

    // 3. AES-GCM 解密
    const decrypted = await crypto.subtle.decrypt(
      { name: ALGORITHM, iv, tagLength: GCM_TAG_LENGTH * 8 },
      key,
      data
    )

    // 4. 尝试 Gzip 解压
    try {
      const decompressed = await gzipDecompress(new Uint8Array(decrypted))
      return new TextDecoder().decode(decompressed)
    } catch {
      // 不是压缩数据，直接返回
      return new TextDecoder().decode(decrypted)
    }
  } catch (e) {
    console.warn('Decryption failed:', e)
    return ciphertext
  }
}

/**
 * 处理加密响应
 * 如果响应包含 encrypted 标记，自动解密
 */
export async function processResponse(
  response: any,
  secretKey: string
): Promise<any> {
  if (!response || !response.encrypted || !response.data) {
    return response
  }

  try {
    const decrypted = await decryptAndDecompress(response.data, secretKey)
    const parsed = JSON.parse(decrypted)

    console.log(
      `[Crypto] Decrypted: ${response.compressedSize} → ${response.originalSize} bytes ` +
      `(${(1 - response.compressedSize / response.originalSize) * 100 | 0}% compression)`
    )

    return parsed
  } catch (e) {
    console.error('[Crypto] Failed to decrypt response:', e)
    return response
  }
}

/**
 * Gzip 压缩
 */
async function gzipCompress(data: Uint8Array): Promise<Uint8Array> {
  const stream = new CompressionStream('gzip')
  const writer = stream.writable.getWriter()
  writer.write(data)
  writer.close()

  const reader = stream.readable.getReader()
  const chunks: Uint8Array[] = []

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    chunks.push(value)
  }

  // 合并 chunks
  const totalLength = chunks.reduce((sum, chunk) => sum + chunk.length, 0)
  const result = new Uint8Array(totalLength)
  let offset = 0
  for (const chunk of chunks) {
    result.set(chunk, offset)
    offset += chunk.length
  }

  return result
}

/**
 * Gzip 解压
 */
async function gzipDecompress(data: Uint8Array): Promise<Uint8Array> {
  const stream = new DecompressionStream('gzip')
  const writer = stream.writable.getWriter()
  writer.write(data)
  writer.close()

  const reader = stream.readable.getReader()
  const chunks: Uint8Array[] = []

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    chunks.push(value)
  }

  // 合并 chunks
  const totalLength = chunks.reduce((sum, chunk) => sum + chunk.length, 0)
  const result = new Uint8Array(totalLength)
  let offset = 0
  for (const chunk of chunks) {
    result.set(chunk, offset)
    offset += chunk.length
  }

  return result
}

/**
 * 检查是否为 Base64 编码
 */
function isBase64(str: string): boolean {
  if (!str) return false
  try {
    atob(str)
    return true
  } catch {
    return false
  }
}
