(ns knope.adapters.crypto
  (:import (java.security SecureRandom)
           (java.util Arrays)
           (javax.crypto Cipher SecretKeyFactory)
           (javax.crypto.spec GCMParameterSpec PBEKeySpec SecretKeySpec)))

;; File layout: "KNOPE" | version 1 | 16-byte salt | 12-byte IV | AES-GCM
;; ciphertext with its 128-bit tag. The key is PBKDF2-HMAC-SHA256, 256 bits.

(def ^:private magic (.getBytes "KNOPE" "US-ASCII"))
(def ^:private version 1)
(def ^:private salt-length 16)
(def ^:private iv-length 12)
(def ^:private tag-bits 128)
(def ^:private header-length (+ (alength ^bytes magic) 1 salt-length iv-length))

(defn- random-bytes [n]
  (let [bs (byte-array n)]
    (.nextBytes (SecureRandom.) bs)
    bs))

(defn- cipher [mode passphrase salt iv iterations]
  (let [spec (PBEKeySpec. (.toCharArray ^String passphrase) salt (int iterations) 256)
        key  (-> (SecretKeyFactory/getInstance "PBKDF2WithHmacSHA256")
                 (.generateSecret spec)
                 .getEncoded
                 (SecretKeySpec. "AES"))]
    (doto (Cipher/getInstance "AES/GCM/NoPadding")
      (.init (int mode) key (GCMParameterSpec. tag-bits iv)))))

(defn encrypt
  [plain passphrase iterations]
  (let [salt       (random-bytes salt-length)
        iv         (random-bytes iv-length)
        ciphertext (.doFinal (cipher Cipher/ENCRYPT_MODE passphrase salt iv iterations) plain)
        out        (byte-array (+ header-length (alength ^bytes ciphertext)))]
    (System/arraycopy magic 0 out 0 (alength ^bytes magic))
    (aset-byte out (alength ^bytes magic) (byte version))
    (System/arraycopy salt 0 out (inc (alength ^bytes magic)) salt-length)
    (System/arraycopy iv 0 out (+ (inc (alength ^bytes magic)) salt-length) iv-length)
    (System/arraycopy ciphertext 0 out header-length (alength ^bytes ciphertext))
    out))

(defn decrypt
  [file passphrase iterations]
  (let [wrong {:error/code :sync/wrong-passphrase}
        m     (alength ^bytes magic)]
    (if (or (< (alength ^bytes file) (+ header-length (quot tag-bits 8)))
            (not (Arrays/equals ^bytes magic ^bytes (Arrays/copyOfRange ^bytes file 0 m)))
            (not= version (aget ^bytes file m)))
      wrong
      (let [salt (Arrays/copyOfRange ^bytes file (inc m) (+ (inc m) salt-length))
            iv   (Arrays/copyOfRange ^bytes file (+ (inc m) salt-length) header-length)
            body (Arrays/copyOfRange ^bytes file header-length (alength ^bytes file))]
        (try
          (.doFinal (cipher Cipher/DECRYPT_MODE passphrase salt iv iterations) body)
          ;; AEADBadTagException cannot be named in babashka.
          (catch Exception _ wrong))))))
