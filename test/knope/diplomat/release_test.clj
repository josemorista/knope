(ns knope.diplomat.release-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [knope.diplomat.release :as release]
            [org.httpkit.server :as server])
  (:import (java.io ByteArrayInputStream) (java.net ServerSocket)))

(def ^:private latest-path "/repos/josemorista/knope/releases/latest")
(def ^:private jar-path "/josemorista/knope/releases/download/v1.2.3/knope.jar")

(defn- port-of [stop] (:local-port (meta stop)))

(defn- with-server
  "Runs f with the base URL of a local server that answers with handler."
  [handler f]
  (let [stop (server/run-server handler {:port 0})]
    (try
      (f (str "http://localhost:" (port-of stop)))
      (finally
        (stop)))))

(defn- config [base]
  (assoc release/default-config
         :api-url base
         :download-url base
         :repo "josemorista/knope"
         :connect-timeout 1000
         :lookup-timeout 2000
         :download-deadline 500))

(defn- closed-port-url []
  (let [port (with-open [socket (ServerSocket. 0)] (.getLocalPort socket))]
    (str "http://localhost:" port)))

(defn- with-temp-dir [f]
  (let [dir (fs/create-temp-dir {:prefix "knope-release-test-"})]
    (try
      (f dir)
      (finally
        (fs/delete-tree dir)))))

(defn- json [status body]
  {:status status :headers {"Content-Type" "application/json"} :body body})

(deftest latest-tag-gives-the-tag-name-of-the-latest-release
  (let [seen (atom nil)]
    (with-server (fn [req]
                   (reset! seen req)
                   (if (= latest-path (:uri req))
                     (json 200 "{\"tag_name\":\"v1.2.3\",\"name\":\"1.2.3\"}")
                     {:status 404}))
      (fn [base]
        (is (= {:ok "v1.2.3"} (release/latest-tag (config base))))
        (is (= "knope" (get-in @seen [:headers "user-agent"])))
        (is (some? (get-in @seen [:headers "accept"])))))))

(deftest latest-tag-is-no-release-when-github-answers-404
  (with-server (fn [_] {:status 404 :body "Not Found"})
    (fn [base]
      (is (= :update/no-release (:error/code (release/latest-tag (config base))))))))

(deftest latest-tag-is-no-release-when-github-refuses-the-request
  (with-server (fn [_] (json 403 "{\"message\":\"API rate limit exceeded\"}"))
    (fn [base]
      (is (= :update/no-release (:error/code (release/latest-tag (config base))))))))

(deftest latest-tag-is-no-release-for-a-body-without-tag-name
  (with-server (fn [_] (json 200 "{\"name\":\"1.2.3\"}"))
    (fn [base]
      (is (= :update/no-release (:error/code (release/latest-tag (config base))))))))

(deftest latest-tag-is-unreachable-when-nothing-listens
  (is (= :update/unreachable
         (:error/code (release/latest-tag (config (closed-port-url)))))))

(deftest a-stalled-lookup-ends-at-the-timeout-as-unreachable
  (with-server (fn [req]
                 (server/as-channel
                  req
                  {:on-open (fn [ch]
                              (server/send! ch {:status 200
                                                :headers {"Content-Type" "application/json"}
                                                :body "{\"tag_name\":"}
                                            false))}))
    (fn [base]
      (let [started (System/nanoTime)
            result  (release/latest-tag (assoc (config base) :lookup-timeout 500))
            elapsed (/ (- (System/nanoTime) started) 1e6)]
        (is (= :update/unreachable (:error/code result)))
        (testing "it ends near the timeout instead of waiting for the body"
          (is (< elapsed 5000)))))))

(deftest download-copies-the-jar-of-the-tag-to-the-destination
  (let [bytes (byte-array (map unchecked-byte (range 0 256)))]
    (with-server (fn [req]
                   (if (= jar-path (:uri req))
                     {:status 200 :body (ByteArrayInputStream. bytes)}
                     {:status 404}))
      (fn [base]
        (with-temp-dir
          (fn [dir]
            (let [dest (str (fs/path dir "knope.jar.tmp"))]
              (is (nil? (release/download! (config base) "v1.2.3" dest)))
              (is (= (seq bytes) (seq (fs/read-all-bytes dest)))))))))))

(deftest download-fails-when-the-release-has-no-jar
  (with-server (fn [_] {:status 404 :body "Not Found"})
    (fn [base]
      (with-temp-dir
        (fn [dir]
          (let [dest (str (fs/path dir "knope.jar.tmp"))]
            (is (= :update/download-failed
                   (:error/code (release/download! (config base) "v1.2.3" dest))))))))))

(deftest download-fails-when-nothing-listens
  (with-temp-dir
    (fn [dir]
      (let [dest (str (fs/path dir "knope.jar.tmp"))]
        (is (= :update/download-failed
               (:error/code (release/download! (config (closed-port-url)) "v1.2.3" dest))))))))

(deftest a-stalled-download-ends-at-the-deadline-and-closes-the-stream
  (let [closed (promise)]
    (with-server (fn [req]
                   (server/as-channel
                    req
                    {:on-open  (fn [ch]
                                 (server/send! ch {:status 200
                                                   :headers {"Content-Type" "application/java-archive"}
                                                   :body "first part"}
                                               false))
                     :on-close (fn [_ _] (deliver closed true))}))
      (fn [base]
        (with-temp-dir
          (fn [dir]
            (let [dest    (str (fs/path dir "knope.jar.tmp"))
                  started (System/nanoTime)
                  result  (release/download! (config base) "v1.2.3" dest)
                  elapsed (/ (- (System/nanoTime) started) 1e6)]
              (is (= :update/download-failed (:error/code result)))
              (testing "it ends near the deadline, not at an HTTP timeout"
                (is (< elapsed 5000)))
              (testing "the connection is closed from the client side"
                (is (true? (deref closed 3000 false)))))))))))
