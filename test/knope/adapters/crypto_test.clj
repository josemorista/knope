(ns knope.adapters.crypto-test
  (:require [clojure.test :refer [deftest is testing]]
            [knope.adapters.crypto :as crypto]))

(def iterations 1000)
(def passphrase "correct horse battery staple")

(defn- utf8 [s] (.getBytes ^String s "UTF-8"))

(def plain (utf8 "SQLite format 3 and then some task rows"))

(defn- wrong-passphrase? [result]
  (= :sync/wrong-passphrase (:error/code result)))

(defn- with-byte-flipped [^bytes bs i]
  (let [copy (aclone bs)]
    (aset-byte copy i (unchecked-byte (bit-xor (aget copy i) 0x01)))
    copy))

(defn- sealed [] (crypto/encrypt plain passphrase iterations))

(deftest decrypt-returns-what-encrypt-sealed
  (is (= (seq plain) (seq (crypto/decrypt (sealed) passphrase iterations)))))

(deftest decrypt-returns-an-empty-input-unchanged
  (let [empty-plain (byte-array 0)]
    (is (= 0 (alength ^bytes (crypto/decrypt (crypto/encrypt empty-plain passphrase iterations) passphrase iterations))))))

(deftest the-file-starts-with-the-knope-marker-and-format-version-one
  (let [file (sealed)]
    (is (= "KNOPE" (String. ^bytes (byte-array (take 5 file)) "UTF-8")))
    (is (= 1 (aget ^bytes file 5)))))

(deftest the-file-holds-salt-iv-ciphertext-and-tag-after-the-header
  (testing "5 marker + 1 version + 16 salt + 12 IV + plaintext + 16 GCM tag"
    (is (= (+ 5 1 16 12 (alength ^bytes plain) 16) (alength ^bytes (sealed))))))

(deftest two-encryptions-of-the-same-bytes-differ
  (is (not= (seq (sealed)) (seq (sealed)))))

(deftest the-plaintext-does-not-appear-in-the-file
  (let [file (String. ^bytes (sealed) "ISO-8859-1")]
    (is (not (.contains file "task rows")))))

(deftest a-wrong-passphrase-is-reported-as-wrong-passphrase
  (is (wrong-passphrase? (crypto/decrypt (sealed) "not the passphrase" iterations))))

(deftest a-flipped-ciphertext-byte-is-reported-as-wrong-passphrase
  (let [file (sealed)]
    (is (wrong-passphrase? (crypto/decrypt (with-byte-flipped file (- (alength ^bytes file) 20))
                                           passphrase iterations)))))

(deftest a-flipped-tag-byte-is-reported-as-wrong-passphrase
  (let [file (sealed)]
    (is (wrong-passphrase? (crypto/decrypt (with-byte-flipped file (dec (alength ^bytes file)))
                                           passphrase iterations)))))

(deftest a-flipped-salt-byte-is-reported-as-wrong-passphrase
  (is (wrong-passphrase? (crypto/decrypt (with-byte-flipped (sealed) 6) passphrase iterations))))

(deftest a-bad-marker-is-reported-as-wrong-passphrase
  (is (wrong-passphrase? (crypto/decrypt (with-byte-flipped (sealed) 0) passphrase iterations))))

(deftest an-unknown-format-version-is-reported-as-wrong-passphrase
  (let [file (aclone ^bytes (sealed))]
    (aset-byte file 5 (byte 2))
    (is (wrong-passphrase? (crypto/decrypt file passphrase iterations)))))

(deftest a-truncated-file-is-reported-as-wrong-passphrase
  (let [file (sealed)]
    (doseq [n [0 3 5 6 20 33 40]]
      (testing (str "first " n " bytes")
        (is (wrong-passphrase? (crypto/decrypt (byte-array (take n file)) passphrase iterations)))))))
